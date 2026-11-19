# cache4j 设计文档

> **状态**：设计稿（v1），本轮只做设计，不写实现。
>
> **范围**：二级缓存内核、本地缓存 SPI、中央缓存 SPI、Redis 缓存 SPI、Caffeine / Spring Data Redis 实现、Spring / Solon 适配。
>
> **关联**：错误码与异常继承 `standard4j` 的 `ResCode` / `SystemException`；核心模块与 Spring、Solon 无关。

---

## 1. 目标与非目标

### 1.1 目标（已定）

1. **二级缓存**：本地缓存（进程内，L1） + 中央缓存（跨进程共享，L2）。
2. **本地三种策略**：
   - `NONE`：本地永不存值，读中央；中央未命中由调用方回源。中央查到的值也不回填本地。
   - `STATIC`：本地优先；未命中查中央并回填本地；本地必须有容量上限与 TTL 淘汰。
   - `DYNAMIC`：借鉴 JD HotKey 的热点探测，按 key 访问频率做热升热降，只有热点 key 进入本地。
3. **per-key 时间戳版本**：中央为**每个 key** 维护一个版本号，取值是"该 key 最后一次写入时的中央时钟时间戳（毫秒）"，并随它记录这个值的**过期时刻**；版本表是该命名空间的一张 `__rev` HASH。本地副本携带 `(value, revision)`；判断一条本地值能否使用，比的是"这条副本的 revision 是否仍是最新"，没有 namespace 级整代失效。
4. **访问驱动的批量对账**：不做后台定时轮询，也不是"每个 key 各自计时"。每个命名空间维护一个同步信号量（`nextSyncAtNanos` + `syncing` CAS）；到点后的下一次访问由当前线程执行一次**批量对账**：`HMGET __rev <该命名空间下本地已知的全部 key>`，把整个命名空间的版本一次性取回、逐条比对，不一致或缺字段的副本被清除，顺手 `HDEL` 已过期的版本 field。没有访问就没有中央 I/O；一次对账覆盖全部本地副本，不存在某个 key 被饿死。
5. **写操作返回该 key 的新版本**：中央写（`put` / 成功的 `putIfAbsent` / `evict` / 负缓存写）与版本更新在同一 Lua 内原子完成，并把新版本号返回给本地；本地用它覆盖该 key 的副本。
6. **跨节点热 key 协调**：`DYNAMIC` 的热升可以本地立即生效；本地不升热时，在同一次访问驱动的同步事件中向中央做补偿查询；降热只认中央判定 `COOLING`，本地频次下降不作数。
7. **中央缓存 SPI**：`cache4j-central` 只定义中央缓存契约（读写、TTL、负缓存、版本、provider），不认识 Redis，也不认识任何 Redis 客户端；其他中央存储按同一组 SPI 扩展。
8. **本地实现可替换**：默认提供零依赖的 HashMap 实现；生产推荐 Caffeine。
9. **Redis 语义层 + 泛型下行 SPI**：`cache4j-central-redis` 定义 `RedisValueStore<V>`（“存一个 V、给我版本”的泛型抽象）与 `RedisCentralCache<V>`（版本、负缓存、TTL 抖动、批量对账、热 key 的**语义**），**不碰字节**；key 布局、envelope、Lua、序列化由真正做 I/O 的适配模块实现。
10. **框架无关**：`cache4j-core` / `local` / `central` / `central-redis` 不引用 Spring、Solon、Jedis、Lettuce、Redisson；框架接入只存在于 `cache4j-spring`、`cache4j-spring-redis`、`cache4j-solon`。
11. **统一错误体系**：缓存基础设施异常继承 `standard4j` 的 `SystemException`，错误码使用 `CACHE-` 前缀。
12. **可观测**：命中、回源、负命中、升降热、版本同步、热同步、中央故障、编解码异常等均有计数；不依赖具体监控后端。

### 1.2 非目标（v1 明确不做）

| 不做 | 原因 / 后续 |
|---|---|
| 三级及以上缓存 | 二级已覆盖绝大多数场景；多级会显著增加一致性与配置复杂度 |
| 跨节点强一致 / 广播失效 | v1 用「per-key 时间戳版本 + 命名空间级批量对账」限定不一致窗口；不引入 pub/sub 广播。对账是拉模型，失败可退化 |
| 跨节点分布式锁 / 跨节点单飞 | 只解决单 JVM 内的缓存击穿；跨节点回源保护后续按需增加 |
| 后台定时同步线程 | 版本同步与热 key 同步都由访问事件触发；没有访问就没有中央 I/O |
| 跨节点精确频率统计 | 中央用窗口计数做热 key 判定，接受窗口边界误差与有界聚合误差 |
| 写穿透到 DB / 读穿透全自动 | `CacheLoader` 由调用方提供；缓存库不猜测数据源 |
| 本地持久化 / 堆外存储 | 不属于通用缓存内核的首要目标 |
| 异步 API | v1 同步阻塞；中央客户端内部可用异步，后续再评估 `CompletableFuture` 门面 |
| Spring / Solon 依赖进入核心 | 核心零框架依赖是硬约束 |
| 内置任何序列化实现 | **编解码归适配层**：真正与中央存储发生 I/O 的模块（如 `cache4j-spring-redis`）自己决定 serializer；核心与中央 SPI 完全不知情 |

---

## 2. 术语

| 术语 | 含义 |
|---|---|
| 本地缓存 / L1 | 进程内缓存，容量有限，可能与其他实例不一致 |
| 中央缓存 / L2 | 跨进程共享的缓存（v1 为 Redis），作为缓存数据的权威副本 |
| 版本号 / revision | 中央为**每个 key** 维护的版本；取值 = 该 key 最后一次写入的中央时钟时间戳（毫秒），只要求"每次写都不同" |
| `LocalEntry` | 本地副本的包装：`(V value, long revision)`；本地 key 就是 `CacheKey`，不再有 generation |
| 同步信号量 | 每个命名空间一个：`nextSyncAtNanos` + `syncing` CAS；到点后的下一次访问触发一次批量对账 |
| 批量对账 / reconcile | 一次 `HMGET __rev <本地已知 key>` 把整个命名空间的版本取回，逐条比对：版本变了或缺字段的副本清掉，顺手 `HDEL` 已过期的版本 field |
| 合并 I/O | 一次中央往返同时返回该 key 的版本与值（`HGET __rev field` + `GET data`，可用 Lua 合成一次往返） |
| 热升 | `DYNAMIC` 策略下，key 进入本地热点集合；本地达到阈值立即生效 |
| 热降 | 把 key 移出本地热点集合；**只有**中央判定 `COOLING` 才允许 |
| 全局热 key | 同一 key 在中央窗口内的集群合计访问频次达到全局阈值 |
| 预选热 key / candidate | 本地频次较高但未达升热阈值的 key；参与上报与补偿查询，是"可能升热"的候选 |
| 上报分区 | 热 key 上报的分片号 = `stableHash(encodedKey) & (N-1)`；一次访问只上报当前 key 所属分区的热 key + 预选 key |
| 回源 | 中央缓存未命中后，调用方通过 `CacheLoader` 查询 DB / API / 文件 |
| 负缓存 | 把"数据不存在"也缓存起来，防止缓存穿透；v1 只写中央，不写本地 |
| 单飞 | 同一个 key 的并发回源只允许一个线程执行 loader，其余线程等待结果 |
| 逻辑 key | `CacheKey`，由缓存名（namespace）与编码后的 key 组成 |
| 存储 key | Redis 中的最终字符串，由逻辑 key 加部署前缀、可选 hash tag 生成 |

---

## 3. 模块与依赖

### 3.1 模块图

```text
cache4j (pom)
├── cache4j-core                 内核：全套 SPI（Cache / LocalCache / CentralCache / 两个 Spec / Provider / HotKeyCoordinator）+ TwoLevelCache 接口 + DefaultTwoLevelCache 默认编排实现，仅依赖 standard4j
├── cache4j-local                本地缓存实现：NONE/STATIC/DYNAMIC + 默认 HashMap 实现
│   └── cache4j-local-caffeine   Caffeine 存储实现
├── cache4j-central              中央缓存 SPI：CentralCache / Spec / Provider + 测试用内存参考实现
│   └── cache4j-central-redis   Redis 语义层 + 泛型下行 SPI（RedisValueStore<V> / RedisCentralCache<V>），不碰字节
├── cache4j-spring               Spring Boot 自动装配 + Spring Cache 桥接（不接 Redis）
├── cache4j-spring-redis         接入 Spring Data Redis：连接 + key 布局 + envelope + Lua + serializer，客户端由用户选择
├── cache4j-solon                Solon 插件 + CacheService（含 tags）桥接
└── cache4j-starter              聚合/默认装配：只拉依赖 + 装配默认实现，**不放编排逻辑**（见 §3.5）
```

依赖方向：

```text
standard4j ← cache4j-core ← cache4j-local ← cache4j-local-caffeine
                          ← cache4j-central ← cache4j-central-redis
                          ← cache4j-spring ← cache4j-spring-redis
                          ← cache4j-solon
```

- `cache4j-core` 定义通用契约 `Cache` / `Lookup` / `CacheKey` / `CacheStats`，`LocalCache` / `CentralCache` 接口、两个 Spec、`CentralCacheProvider`、`HotKeyCoordinator`，以及 `TwoLevelCache` **接口** + `DefaultTwoLevelCache` 默认编排实现；它不依赖任何实现模块，业务可用自己的门面实现替换默认编排。
- `cache4j-local` 与 `cache4j-central` **同级**，各自只依赖 `cache4j-core`，互不依赖；`cache4j-local-*` 依赖 `cache4j-local`，`cache4j-central-*` 依赖 `cache4j-central`，父子模块同级排列，不做聚合 `pom`。
- `cache4j-central` 定义中央缓存 SPI：`CentralCache`、`CentralCacheSpec`、`CentralCacheProvider`、`HotKeyCoordinator`；附带的内存实现仅用于测试与单机，不作为生产存储。
- `cache4j-central-redis` 定义 **Redis 语义层 + 泛型下行 SPI**：`RedisValueStore<V>`（泛型，无字节）、`RedisCentralCache<V>`、`RedisHotKeyCoordinator`、`RedisAccessException`；不依赖任何 Redis 客户端（Jedis / Lettuce / Redisson），也不依赖 Spring，更不关心序列化。
- `cache4j-spring-redis` 是**真正与 Redis 发生 I/O 的模块**：用 `RedisConnectionFactory` 实现 `RedisValueStore<V>`，并自己负责 key 布局、envelope、Lua 与 serializer；底层用 Jedis、Lettuce 还是其他客户端，由用户在 Spring 侧选择。
- `cache4j-spring` 只做 Spring Cache / Boot 装配，不直接接 Redis；`cache4j-spring-redis` 只做 Redis 连接适配。
- `cache4j-solon` 只做装配与协议转换，不复制缓存逻辑。
- 非 Spring 的 Redis 客户端适配不在 v1：使用方可以实现 `RedisValueStore<V>`，或后续新增 `cache4j-redis-lettuce` / `cache4j-redis-jedis` / `cache4j-spring-redisson` 这类独立适配模块，同样不反向进入 `cache4j-central-redis`。

Redis 方向的三层 SPI：

| 层 | 模块 | 定义 | 禁止 |
|---|---|---|---|
| 中央缓存 SPI | `cache4j-central` | 中央缓存读写、TTL、负缓存、版本、热 key 协调、provider 契约 | 出现 Redis 概念、出现任何客户端 |
| Redis 语义层 + 下行 SPI | `cache4j-central-redis` | `RedisValueStore<V>` 泛型契约、`RedisCentralCache<V>` 语义（版本/负缓存/TTL/热 key） | 出现字节、key 布局、envelope、Lua、serializer、任何客户端 |
| 适配层（真正做 I/O） | `cache4j-spring-redis` | 连接、key 布局、envelope、Lua、**serializer** | 复制缓存语义；锁定底层客户端 |

### 3.2 为什么把编排放在 `cache4j-core`

不新增独立编排模块，是为了让模块清单与用户给出的规则一致：`cache4j` 只做父工程，功能模块按 `cache4j-*` / `cache4j-*-*` 平铺。

编排角色的三个候选：

| 方案 | 结果 | 结论 |
|---|---|---|
| 放 `cache4j-local` | `local` 必须依赖 `central`，本地实现模块承担跨层职责；`central-redis` 也会被间接拖进 `local` 的依赖树 | 否 |
| 放 `cache4j-central` | `central` 必须依赖 `local`，"中央依赖本地"与直觉相反 | 否 |
| 放 `cache4j-core` | `core` 只定义接口与门面，不依赖任何实现；`local` / `central` 各自实现 | **采用** |

边界说明：

- `cache4j-core` 里有 `LocalCache`、`CentralCache`、`LocalCacheSpec`、`CentralCacheSpec`、`HotKeyCoordinator`、`TwoLevelCache`，但**没有** Caffeine、HashMap 分段 LRU、Redis 命令、envelope 实现；
- `cache4j-core` 不认识任何具体实现类型，只面向接口；
- 门面需要"同时看到两层"，但看到的是**接口**，不是实现模块，因此没有反向依赖；
- `cache4j-local` / `cache4j-central` 只回答"本地/中央怎么存"，不回答"两层怎么配合"。

`TwoLevelCache` 在 core 中承接：

- 三种策略的读路径；
- 版本对账（命名空间级信号量 + 批量 `HMGET` 比对）、副本覆盖与清理、合并 I/O 的编排；
- 写穿、失效、清空，以及中央返回版本号的处理；
- `DYNAMIC` 热 key 的访问驱动同步与中央状态应用；
- 单飞加载、负缓存、降级；
- 跨层统计聚合。

### 3.3 框架无关的硬约束

- `cache4j-core`、`cache4j-local`、`cache4j-local-caffeine`、`cache4j-central`、`cache4j-central-redis` 的 `pom.xml` **不得**出现 Spring / Solon / Jedis / Lettuce / Redisson 依赖。
- Caffeine 只允许出现在 `cache4j-local-caffeine`；cache4j 的任何模块都不声明 Jedis / Lettuce / Redisson 依赖。`cache4j-spring-redis` 只依赖 `spring-data-redis` 的 API，运行时客户端由用户引入并选择。
- `cache4j-central-redis` 只定义 Redis 缓存 SPI；`RedisCentralCache` / key 布局 / envelope / 编解码逻辑完全不感知客户端类型。
- `cache4j-spring-redis` 用 Spring Data Redis 实现 `RedisValueStore<V>`；它不重复版本/热 key 语义，只做连接适配、key/envelope/Lua 与序列化，也不锁定底层客户端。
- 通过 `maven-enforcer-plugin` 的 `bannedDependencies` 或 ArchUnit 测试守住边界；CI 失败即视为破坏架构。
- 所有框架注解、自动装配、`CacheManager` / `CacheService` 适配都只放在 `cache4j-spring` / `cache4j-spring-redis` / `cache4j-solon`。

### 3.4 坐标与包名

| 项 | 约定 |
|---|---|
| `groupId` | `com.asialjim` |
| 父工程 `artifactId` | `cache4j`，`packaging=pom`，版本 `1.0.0-SNAPSHOT` |
| 子模块 | `cache4j-core`、`cache4j-local`、`cache4j-local-caffeine`、`cache4j-central`、`cache4j-central-redis`、`cache4j-spring`、`cache4j-spring-redis`、`cache4j-solon` |
| 打包方式 | **只有**父工程 `cache4j` 是 `pom`；`cache4j-local`、`cache4j-central` 及其 `-*` 子模块全部是 `jar`，不做聚合 `pom` |
| 包前缀 | `com.asialjim.cache4j` |
| 子包 | `core`、`core.local`、`core.central`、`core.twolevel`、`local`、`local.caffeine`、`central`、`central.redis`、`spring`、`spring.redis`、`solon` |
| Java | 17 |
| 编码 | UTF-8 |

### 3.5 `cache4j-starter` 的定位（不放编排逻辑）

`cache4j-starter` 只做两件事：**依赖聚合**（把 core / local / central 等模块拉进来，用户引一个坐标即可）和**默认装配**（按配置创建默认实现）。它**不**承载 `TwoLevelCache` 的编排逻辑：

- 编排（读/写路径、版本校验、单飞、降级、热 key 协调）全部在 `cache4j-core` 的 `DefaultTwoLevelCache`，是框架无关的核心能力；
- 把编排放进 starter 会让非 Spring / 非 Solon 用户也得引一个名叫 "starter" 的模块，语义错乱；且 `cache4j-spring` / `cache4j-solon` 这些适配层会反过来依赖 starter，容易绕成环；
- Spring 用户若希望"一个依赖搞定"，应另加 `cache4j-spring-boot-starter`（依赖 `cache4j-spring` + `cache4j-spring-redis`，遵循 Boot 命名惯例），而不是让 `cache4j-starter` 承担框架装配。

---

## 4. 核心契约（`cache4j-core`）

### 4.1 `Lookup`：命中 / 未命中 / 负命中

`Optional` 无法区分"没有缓存"与"缓存了 null"。缓存穿透防护又必须区分这两者，因此核心用三态结果：

```java
public sealed interface Lookup<V> permits Lookup.Hit, Lookup.Negative, Lookup.Miss {

    record Hit<V>(V value) implements Lookup<V> {
        public Hit { Objects.requireNonNull(value, "命中值不允许为 null"); }
    }

    /** 明确知道数据不存在，且这个“不存在”仍在有效期内 */
    record Negative<V>() implements Lookup<V> {}

    /** 没有缓存，需要回源 */
    record Miss<V>() implements Lookup<V> {}

    static <V> Lookup<V> hit(V value) { return new Hit<>(value); }
    static <V> Lookup<V> negative()    { return new Negative<>(); }
    static <V> Lookup<V> miss()        { return new Miss<>(); }

    default boolean isHit()      { return this instanceof Hit<?>; }
    default boolean isNegative() { return this instanceof Negative<?>; }
    default boolean isMiss()     { return this instanceof Miss<?>; }

    @SuppressWarnings("unchecked")
    default Optional<V> optional() {
        return isHit() ? Optional.of(((Hit<V>) this).value()) : Optional.empty();
    }
}
```

语义：

| 结果 | 含义 | 是否调用 loader | 是否算命中 |
|---|---|---|---|
| `Hit(value)` | 有值 | 否 | 是（正命中） |
| `Negative` | 已确认不存在，且负缓存未过期 | 否 | 单独计为负命中 |
| `Miss` | 没有可用缓存 | 是（如果提供了 loader） | 否 |

对外便利方法 `Optional<V> get(K key)` 内部调用 `lookup`，`Hit` 返回值，`Negative` 与 `Miss` 都返回 `Optional.empty()`。需要区分时使用 `lookup` 或 `containsKey`。

`containsKey`：`Hit` 与 `Negative` 都返回 `true`。

### 4.2 `Cache` 接口

```java
public interface Cache<K, V> extends AutoCloseable {

    String name();

    Lookup<V> lookup(K key);

    default Optional<V> get(K key) {
        return lookup(key).optional();
    }

    boolean containsKey(K key);

    /** ttl 为 null 表示使用缓存默认 TTL */
    void put(K key, V value, @Nullable Duration ttl);

    default void put(K key, V value) {
        put(key, value, null);
    }

    /** 原子“不存在才写入”；负缓存视为已存在 */
    boolean putIfAbsent(K key, V value, @Nullable Duration ttl);

    void evict(K key);

    void clear();

    CacheStats stats();

    @Override
    default void close() {
        // 默认无资源
    }
}
```

约定：

- 所有实现必须线程安全。
- `lookup` 不做回源；回源只发生在带 `CacheLoader` 的 `get` 中。
- `put(key, null)` 的语义见 §5.4：启用负缓存时写负缓存，否则等价于 `evict`。
- `putIfAbsent` 在中央不可用且 `failOpen=true` 时返回 `false`（无法保证原子性），不静默写成"本地成功"。
- `Cache` 不提供 `size()`：跨进程的"总大小"没有稳定语义；需要时看 `stats().estimatedSize()`（仅本地/近似）。
- `close` 必须幂等。

### 4.3 `CacheKey` 与 `KeyEncoder`

#### 4.3.1 `CacheKey` 与本地副本

同一份 key 要同时经过三类介质：

1. 本地缓存：对象 key 的 `equals/hashCode`；
2. Redis：必须是字符串；
3. 日志 / 排障：需要可读、可复现。

如果各层各自转换，`Long 42L` 与 `String "42"` 可能在 Redis 侧都变成 `"42"`，造成串数据；`Object.toString()` 默认带对象哈希，进程重启即变化，导致缓存永不命中。因此 key 必须**集中编码一次**：本地与中央使用**同一个** `CacheKey`（本地副本额外携带版本与校验时间，见下）。

```java
public record CacheKey(String namespace, String encoded) {

    public CacheKey {
        // namespace 规则见 §4.3.5；encoded 不能为空
    }

    /** 仅用于日志/展示；不参与存储 key 的解析 */
    public String logical() {
        return namespace + ':' + encoded;
    }
}
```

- `namespace`：缓存名，例如 `user:detail`。
- `encoded`：`KeyEncoder` 产出的规范字符串。
- **本地与中央都用这个 `CacheKey`**：本地存储的 key 是 `CacheKey`，中央 Redis 存储 key 不带版本。
- 版本不进入 key：每条本地副本在 value 位置携带自己的 `revision` 与**条目种类**：

```java
public record LocalEntry<V>(V value, long revision, EntryKind kind) {

    public enum EntryKind { VALUE, NEGATIVE }   // NEGATIVE 时 value == null

    static <V> LocalEntry<V> hit(V v, long rev) { return new LocalEntry<>(v, rev, VALUE); }
    static <V> LocalEntry<V> negative(long rev)  { return new LocalEntry<>(null, rev, NEGATIVE); }
}
```

- `revision`：这条副本对应的 key 级版本（中央时间戳），对账时与中央版本比对；
- `kind`：`VALUE` 是真实值，`NEGATIVE` 是"已确认不存在"（仅当 `nullTtl > 0` 时出现，见 §5.4）；用显式枚举而不是 `value == null`，避免破坏 `Lookup.Hit` 的非空不变式；
- 失效是**按 key** 的：对账发现不一致就清除该条，下一次访问回中央重取；不存在"整代切换"，也不存在旧代条目待回收。

**本地副本与中央存储的关系**

```text
本地：CacheKey("user:detail", "s2:42") → LocalEntry(value=…, revision=1795049239456)
中央：CacheKey("user:detail", "s2:42")
      → 数据：cache4j:{user:detail}:s2:42                     (SET … PX)
      → 版本：cache4j:{user:detail}:__rev  HASH  s2:42 → 1795049239456
```

另一个 key 变了版本，与本条无关；`clear` 删整张 `__rev` HASH，于是所有本地副本都因"版本不存在 ≠ 本地 revision"而重新回中央。

#### 4.3.2 规范编码（`KeyEncoders.canonical()`）

目标：**确定性、单射、长度有界、跨进程稳定、可读**。

编码单元：

```text
<type><length>:<payload>
```

- `type`：单字符类型标签；
- `length`：payload 的 UTF-8 字节长度（十进制）；
- 复合类型的 `<length>` 位置是元素/条目个数，后接 `[...]` 或 `{...}`；
- 所有分隔符都在自定界 payload 之外，因此不会与内容冲突。

类型表：

| 标签 | 类型 | payload 规则 | 示例 |
|---|---|---|---|
| `n` | `null` | 无 | `n;` |
| `s` | `String` | UTF-8 | `s2:42` |
| `i` | `Integer` | `toString` | `i2:42` |
| `l` | `Long` | `toString` | `l2:42` |
| `h` | `Short` | `toString` | `h2:42` |
| `y` | `Byte` | `toString` | `y2:42` |
| `B` | `Boolean` | `1` / `0` | `B1:1` |
| `C` | `Character` | UTF-8 | `C1:a` |
| `f` | `Float` | `Float.toString` | `f3:1.5` |
| `d` | `Double` | `Double.toString` | `d3:1.5` |
| `D` | `BigDecimal` | `stripTrailingZeros().toPlainString()`，零统一为 `0` | `D1:0` |
| `u` | `UUID` | 标准 36 位 | `u36:...` |
| `t` | `Instant` | ISO-8601（UTC） | `t...` |
| `L` | `LocalDate` | ISO-8601 | `L10:2026-11-19` |
| `T` | `LocalDateTime` | ISO-8601 | `T...` |
| `O` | `OffsetDateTime` | ISO-8601 | `O...` |
| `Z` | `ZonedDateTime` | ISO-8601（含区域） | `Z...` |
| `e` | `Enum` | `FQCN#name` | `e...` |
| `x` | `byte[]` | Base64 URL 无填充 | `x4:YWJj` |
| `p` | `Optional` | `p0;` 或 `p1[<seg>]` | `p1[s1:a]` |
| `a` | 数组 | `a<count>[seg,seg,...]` | `a2[i1:1,i1:2]` |
| `c` | `Collection` | `c<count>[seg,seg,...]` | `c2[s1:a,i1:1]` |
| `m` | `Map` | `m<count>{k=v,k=v}`，按编码后的 key 排序 | `m1[s1:k=i1:1]` |
| `k` | `KeyWritable` | 自定义 `writeKey` 输出，外层加类型与长度 | `k...` |
| `h` | 段哈希 | 超长段的 SHA-256（Base64 URL，43 字符） | `h43:...` |
| `H` | 整键哈希 | 整键超长时的 SHA-256 | `H43:...` |

> 注：`h` 同时出现在 `Short` 与"段哈希"两个位置，实际不会混淆——`Short` 的 payload 是数字，哈希段固定 43 位 Base64；实现上建议把段哈希标签改为 `#`，避免阅读歧义。本文表格暂保留 `h`，编码落地时以常量表为准。

规范化细节：

- **类型进 key**：`42L` → `l2:42`，`"42"` → `s2:42`，两者不相等。
- **String 长度按 UTF-8 字节**，不是 `char` 数；保证 Redis key 的字节长度可控。
- **BigDecimal**：去尾零、禁止科学计数法；`1.0` 与 `1.00` 编码相同。
- **Set**：先对每个元素编码，再按字典序排序后拼接，保证与迭代顺序无关。
- **Map**：先对 key、value 分别编码，按编码后的 key 排序；key 重复视为非法输入。
- **Collection**：保留迭代顺序（`List` 的语义）；`Set` 走排序规则。
- **数组**：原始类型数组与对象数组统一逐元素编码。
- **Enum**：包含 FQCN，避免两个枚举的同名常量碰撞；FQCN 过长时整段哈希。
- **超长段**：单段 UTF-8 超过 64 字节时，对"类型 + 长度 + payload"整体做 SHA-256，输出 `h43:...`；哈希输入包含类型与长度，避免跨类型碰撞。
- **超长整键**：`namespace + ':' + encoded` 超过 512 字节时，对完整编码做 SHA-256，输出 `H43:...`，保证存储 key 有界。
- **不支持的类型**：直接抛 `CacheException(KEY_ENCODE_FAILED)`，**绝不**回退到 `Object.toString()`。如果业务确实需要，实现 `KeyWritable` 或提供自定义 `KeyEncoder`。
- **自定义扩展**：

```java
public interface KeyWritable {
    void writeKey(KeySink sink);
}

public interface KeySink {
    KeySink text(String value);
    KeySink number(long value);
    KeySink bool(boolean value);
    KeySink beginArray(int size);
    KeySink endArray();
    // ...
}
```

> 设计取舍：强制显式实现比"看起来能用、重启后全部 miss"的 `toString` 回退更安全。旧 `microapplet/cache` 的 key 编码没有类型标签，本库不复用其格式；确需迁移时提供 `KeyEncoders.legacyMicroapplet()` 兼容编码器，作为显式选项，不设为默认。

#### 4.3.3 `KeyEncoder` SPI

```java
@FunctionalInterface
public interface KeyEncoder {
    String encode(Object key);
}
```

内置：

| 工厂方法 | 用途 |
|---|---|
| `KeyEncoders.canonical()` | 默认；上述规范编码 |
| `KeyEncoders.identity()` | 仅接受 `String`，原样返回；Solon 等"key 已是字符串"的适配器使用 |
| `KeyEncoders.legacyMicroapplet()` | 旧库格式兼容，仅迁移期使用 |
| `KeyEncoders.delegate(Function<Object, String>)` | 业务自定义，需自行保证确定性与单射 |

要求：实现必须线程安全、确定性、无随机数、无时间戳、无对象身份依赖。

#### 4.3.4 多参数 key

```java
default String encode(Object... parts) {
    return encode(parts); // 编码为 a<count>[...]，与单参数数组一致
}
```

Spring 的 `SimpleKey`、Solon 的自动 key 都由适配器转成参数数组后调用该入口；禁止把 `SimpleKey.toString()` 直接当 key。

#### 4.3.5 namespace 规则

- 非空，长度 1–64；
- 允许 `[A-Za-z0-9._:-]`；
- 禁止 `{`、`}`（Redis hash tag 语法）、空白、控制字符；
- 不区分大小写比较仅用于"重复缓存名"校验，实际存储保留原始大小写；
- 同一个 `TwoLevelCache` 的 `CacheKey.namespace` 固定等于缓存名。

### 4.4 编解码：不在本库核心

**规则：谁的 I/O 谁关心 codec。**

- `cache4j-core`：只做编排与契约，**不知道编解码这回事**；
- `cache4j-local`：本地存的是 `LocalEntry<V>` 对象，**无需编解码**；
- `cache4j-central`：中央 SPI + 内存实现（直接持有 `V`），**无需编解码**；
- `cache4j-central-redis`：`RedisValueStore<V>` 的下行 SPI 与版本/负缓存/热 key **语义**，**不碰字节**；
- 适配层（`cache4j-spring-redis`、未来 `cache4j-spring-redisson` / `cache4j-solon-redis` / `cache4j-solon-redisson`）：
  真正与 Redis 发生读写，**自己决定** key 布局、envelope、Lua 与 serializer。

推论与代价：

| 推论 | 说明 |
|---|---|
| 不绑定 JSON 库 | 核心零依赖保持不变 |
| 各适配器格式独立 | `spring-redis` 与 `spring-redisson` 的数据**互不兼容**；同一 key 空间必须统一适配器 + serializer |
| 换适配器/serializer | 必须 bump `globalPrefix` 或清空缓存，否则解码失败（显式抛 `CODEC_DECODE_FAILED`，不会静默当 miss） |
| 数据损坏可见 | 序列化/反序列化失败**永远不降级**，必须抛异常 |

> 具体到 Redis 家族的分层、`RedisValueStore<V>` 契约与 envelope 参考格式，见
> [CENTRAL §4](CENTRAL.md) 与 [ADAPTERS](ADAPTERS.md)。

### 4.5 `CacheLoader` 与单飞

```java
@FunctionalInterface
public interface CacheLoader<K, V> {
    V load(K key); // 允许返回 null；允许抛运行时异常
}
```

- loader 的异常**原样向上抛**，不包装成缓存异常；缓存库只负责统计 `loadFailureCount` 并保证不写入任何值。
- loader 返回 `null`：`sourceNullCount +1`，再按 §5.4 决定是否写负缓存（`nullTtl > 0` → 两层都写负副本；否则两层都不写）。
- loader 内不要对同一个 key 调用同一个缓存的 `get(key, loader)`，否则可能自等待；不同 key 可以并发。
- `CacheLoader` 可能被多个线程同时调用（不同 key），实现需线程安全。

单飞算法（`cache4j-core` 的 `TwoLevelCache`）：

```text
get(key, loader):
  l = lookup(key)
  if l 不是 Miss: return l

  future = inflight.computeIfAbsent(cacheKey, k -> new CompletableFuture<>())
  if 本次调用创建了 future:
      try:
          again = lookup(key)          // 双重检查，避免创建 future 前别人已回填
          if again 不是 Miss: complete(again); return again
          value = loader.load(key)
          result = value == null ? writeNegative(key) : writeValue(key, value)
          complete(result)
          return result
      catch (Throwable t):
          completeExceptionally(t)
          throw t
      finally:
          inflight.remove(cacheKey)

  else:
      return future.join()              // 等待 leader 的结果
```

- 单飞范围：**单个 JVM、单个 `TwoLevelCache` 实例**；不同实例、不同节点之间不共享。
- 等待者不重复回源；leader 失败时等待者收到同一异常。
- v1 不设置独立的等待超时；loader 自身应设置 DB/HTTP 超时。后续可增加 `loadTimeout`，超时后等待者退化为自行回源（需重新评估击穿风险）。
- 跨节点击穿由中央 TTL 抖动、负缓存、业务限流共同缓解；分布式锁列为 v2。

### 4.6 统计

统计分三层，**互不重叠、绝不相加**。只有「审计层」参与 `hitRate` 等派生量；「控制层」用于调参，「诊断层」用于排障。

#### 4.6.1 审计层（门面唯一口径）

```java
public interface CacheStats {

    // ① 审计层：业务读的漏斗（只有 TwoLevelCache 记，子层归 0）
    long requestCount();            // 业务读请求数（入口记一次，单飞 double-check 不重复记）
    long localHitCount();           // L1 命中真值
    long localNegativeHitCount();   // L1 命中"已确认不存在"
    long centralHitCount();         // L2 命中真值
    long centralNegativeHitCount(); // L2 命中"已确认不存在"
    long missCount();               // 需要回源
    long sourceLoadCount();         // loader 实际执行次数（单飞后）
    long sourceNullCount();         // loader 返回 null 的次数（穿透量化）
    long loadFailureCount();        // loader 抛异常次数
    long putCount();
    long evictCount();
    long clearCount();

    // ② 控制层：机制健康度（DYNAMIC / 版本对账，不参与审计派生量）
    long promotionCount();
    long demotionCount();
    long localPromotionCount();
    long globalPromotionCount();
    long globalDemotionCount();
    long dynamicPutDiscarded();     // DYNAMIC：冷 key 的写被丢弃次数（口径见 §11 #18）
    long hotKeyCount();             // 快照：当前热点集合大小
    long candidateCount();          // 快照：当前预选桶大小
    long hotSyncCount();
    long hotSyncErrorCount();
    long hotReportDropped();
    long hotCompensationQueryCount();
    long versionSyncCount();        // 对账轮次
    long versionSyncErrorCount();
    long versionInvalidationCount();// 对账清除的本地副本数
    long deadFieldDropped();
    long backfillDiscardedCount();

    // ③ 诊断层：存储自身（local / central 实现各自持有，门面只给本地容量）
    long storeHitCount();           // ← 存储层口径，禁止与上面同名
    long storeMissCount();
    long commandCount();
    long centralErrorCount();
    long codecErrorCount();
    long estimatedSize();           // 门面：本地条目数；中央实现返回 -1

    // ④ 派生量
    double protectionRate();        // 1 - miss/request          端到端下游保护率（含空值缓存）
    double localProtectionRate();   // (localHit+localNeg)/request     L1 对 L2 的保护
    double centralProtectionRate(); // (centralHit+centralNeg)/(request-L1保护量)  L2 对数据源的条件保护
    double valueHitRate();          // (localHit+centralHit)/request   值命中率（只算真值）
    double negativeRatio();         // 负命中 /（全部命中+负命中）   确定性结论里空值占多少
    double nullLoadRatio();         // sourceNullCount / sourceLoadCount  回源里几成是白跑
    double hitRate();               // = valueHitRate()，保留旧名以免误解

    CacheStatsSnapshot snapshot();
}
```

**不变量（TCK 必须断言）**

```text
requestCount == localHitCount + localNegativeHitCount + centralHitCount
                + centralNegativeHitCount + missCount

1 - protectionRate == (1 - localProtectionRate) × (1 - centralProtectionRate)
```

第一条防止四格计数串位；第二条证明三层是**漏斗串联**，不是并列相加。

**计入 / 不计入清单**

| 计入 `requestCount` | 不计入 |
|---|---|
| 业务 `get` / `lookup`（每次入口只记 1 次） | 对账（只读版本）、热 key 上报、`put`/`evict`/`clear`、单飞的 double-check 重查 |

**口径约定**

- `hitRate()` 保留为 `valueHitRate()` 的别名（剔除负命中的"值命中率"）；要做运维决策用 `protectionRate()`，两者差额由 `negativeRatio()` 解释；
- `nullTtl == 0` 时 `*NegativeHitCount ≡ 0`，`protectionRate == valueHitRate`，两个口径自动退化成同一个；
- 存储实现把审计层字段返回 `0`、`estimatedSize` 返回 `-1`（中央不保证大小）；**不提供 `hitRate` 这类派生量的存储实现不允许拼接**；
- `statsEnabled=false` 时全部计数器换成 **no-op**（不在热路径加 `if` 分支），派生量返回 `-1`（表示未统计，不用 `0` 与"真的全 miss"混淆）。默认值 `true`，需要零开销时显式关闭。
- 计数用 `LongAdder`；`CacheStatsSnapshot` 是不可变记录，**同时实现 `CacheStats`**（组件访问器与接口方法同名），
  因此快照既可用于打点，也可直接当作统计视图返回；
- **门面的 `stats()` 是"合成快照"**：审计层（四格漏斗 / 回源）与版本层取门面自己的计数，
  热 key 控制层（`promotion*` / `demotion*` / `hotSync*` / `hotReportDropped` / `dynamicPutDiscarded` /
  `hotKeyCount` / `candidateCount`）取本地装饰器的计数。**每个字段只有唯一来源**，绝不相加。
- **审计禁忌**：不得跨节点算术平均（用 `Σhit/Σ分母` 加权）、不得跨服务横向比较（`hitRate` 与访问分布强相关，只能纵向对比）。

### 4.7 异常与错误码

```java
public class CacheException extends SystemException {
    public CacheException(ResCode resCode) { super(resCode); }
    public CacheException(ResCode resCode, String message) { super(resCode, message); }
    public CacheException(ResCode resCode, String message, Throwable cause) { super(resCode, message, cause); }
}
```

`CacheResCode`（`CACHE-` 前缀，遵循 standard4j 的"模块前缀 + 数字"规范）：

| 错误码 | 枚举 | HTTP 语义 | 场景 |
|---|---|---|---|
| `CACHE-00001` | `INVALID_SPEC` | 500 | 配置非法：TTL 为负、STATIC 未设容量、DYNAMIC 阈值越界等 |
| `CACHE-00002` | `KEY_ENCODE_FAILED` | 500 | key 类型不支持、自定义编码器抛错 |
| `CACHE-00003` | `CODEC_ENCODE_FAILED` | 500 | 值序列化失败 |
| `CACHE-00004` | `CODEC_DECODE_FAILED` | 500 | 值反序列化失败、envelope 损坏 |
| `CACHE-00005` | `CENTRAL_UNAVAILABLE` | 503 | Redis 连接失败、命令失败且未降级 |
| `CACHE-00006` | `CENTRAL_TIMEOUT` | 504 | Redis 命令超时 |
| `CACHE-00007` | `LOCAL_INIT_FAILED` | 500 | Caffeine / HashMap 初始化失败 |
| `CACHE-00008` | `LOAD_TIMEOUT` | 504 | 预留：单飞等待超时（v1 未启用） |

约定：

- 缓存**基础设施**故障用 `SystemException`；loader 内部的业务异常保持原类型，不被 `CacheException` 包裹。
- 配置错误在**启动/构建阶段**抛出，不等到第一次访问。
- `failOpen=true` 时，中央故障被记录并降级，不抛给业务；`failOpen=false` 时抛 `CacheException`。
- 编解码错误**永远不降级**：它是数据错误，不是可用性问题。

---

## 5. 二级缓存编排（`cache4j-core` 的 `TwoLevelCache`）

### 5.1 读路径

```java
Lookup<V> lookup(K key) {
    CacheKey ck = encoder.encode(key);      // 失败抛 KEY_ENCODE_FAILED
    stats.request();
    long now = clock.nanoTime();

    if (local.available()) {
        // 1) 访问事件（按逻辑 key）：DYNAMIC 记频 + 到点才产生的热 key 同步；无访问不触发
        local.access(ck);                   // DYNAMIC 记频；STATIC 无副作用
        local.hotSync(ck);                  // 到点才产生一次上报+状态查询

        // 2) 命名空间级同步信号量：到点则由当前线程做一次批量对账（见 §5.1.1）
        versionGate.ensureInitialized(ck.namespace(), now);      // 首次访问只设点，不 I/O
        if (versionGate.due(ck.namespace(), now)
                && versionGate.tryAcquire(ck.namespace())) {
            try {
                reconcile(ck.namespace());        // 批量取版本 → 清除不一致副本
                versionGate.reschedule(ck.namespace(), now);
            } catch (RuntimeException e) {
                versionGate.backoff(ck.namespace(), now);        // 不推进任何版本，只推迟下次
                if (!failOpen) throw e;
                stats.versionSyncError();
            } finally {
                versionGate.release(ck.namespace());
            }
        }
    }

    // 3) 本地命中：对账后仍未清除的副本就是最新的；区分"真值"与"已确认不存在"
    if (local.available()) {
        LocalEntry<V> l = local.entry(ck);
        if (l != null) {
            return switch (l.kind()) {
                case VALUE    -> { stats.localHit();         Lookup.hit(l.value()); }
                case NEGATIVE -> { stats.localNegativeHit(); Lookup.negative();   }
            };
        }
    }

    // 4) 本地没有 → 一次合并 I/O 取该 key 的版本 + 值
    VersionedLookup<V> c = central.lookupWithRevision(ck);
    if (c.lookup().isHit()) {
        stats.centralHit();
        backfillLocal(ck, LocalEntry.hit(c.lookup().value(), c.revision()));
        return c.lookup();
    }
    if (c.lookup().isNegative()) {
        stats.centralNegativeHit();
        if (centralSpec.nullTtl().isPositive()) {
            // 业务允许缓存空值 → 本地也存负副本（否则 L1 对这类查询完全失效，穿透只从 DB 转到 Redis）
            backfillLocal(ck, LocalEntry.negative(c.revision()));
        } else {
            local.evict(ck);            // 关闭空值缓存：本地不能留旧值
        }
        return c.lookup();
    }

    local.evict(ck);                    // 中央没有这个 key：清掉本地旧副本
    stats.miss();
    return Lookup.miss();
}
```

> **记账锚点**：`requestCount` 只在本方法**入口记一次**。单飞的 double-check 重查（§4.5）走内部重入路径，**不再计数** —— 否则一次业务 `get` 在 miss 路径上会被记成 2 次 request / 2 次 miss，系统性拉低 `hitRate`。同理，对账、热 key 上报、`put`/`evict` 均不计入 `requestCount`。

三种策略的差异只体现在 `local` 的行为和 `backfillLocal`：
| 策略 | `local.available()` | 版本对账 | 本地读 | 中央命中回填 | 中央未命中回源后回填 |
|---|---|---|---|---|---|
| `NONE` | `false` | 不需要（没有本地副本） | 跳过 | 不回填 | 不回填 |
| `STATIC` | `true` | **需要** | 直接查 | 回填 | 回填 |
| `DYNAMIC` | `true` | **需要**（版本 + 热 key） | 仅热点 key 可能命中 | 仅当 key 已热时回填 | 仅当 key 已热时回填 |

#### 5.1.1 批量对账（reconcile）的语义

```java
/**
 * 一次批量取版本，把该命名空间下「本地已知的全部 key」的版本取回来，逐条比对。
 * 版本表自身的清理（TTL 死 field）由存储实现负责，对这里透明。
 */
void reconcile(String namespace) {
    if (!local.available()) return;

    Set<CacheKey> localKeys = local.keys();          // 本地当前条目（≤ maxSize）
    if (localKeys.isEmpty()) return;

    for (List<CacheKey> batch : partition(localKeys, version.reconcileBatchSize)) {  // 默认 1000
        Map<CacheKey, Long> latest = central.revisions(batch);   // 只取版本，不取数据值

        for (CacheKey ck : batch) {
            Long revision = latest.get(ck);
            if (revision == null) {                  // 中央已不再持有（删除 / 清空 / 数据过期）
                local.evict(ck);
                stats.versionInvalidation();
                continue;
            }
            LocalEntry<V> e = local.entry(ck);
            if (e != null && e.revision() != revision) {   // 版本变了
                local.evict(ck);
                stats.versionInvalidation();
            }
        }
    }
}
```

- 信号量属于**命名空间**：`nextSyncAtNanos` + `syncing` CAS；首次访问只设点，**不触发**对账；
- 到点后由**当前访问线程**执行一次对账，完成后 `reschedule(now + checkInterval ± jitter)`；同一命名空间同一时刻只允许一个线程对账；
- **一次对账覆盖全部本地副本**，不存在"哪个 key 轮不到校验"的问题（这是它相对 per-entry 校验的主要优势）；
- 载荷 = **本地已知 key 数**（≤ `maxSize`），不是该命名空间在中央的 key 总数；按 `reconcileBatchSize`（默认 1000）分批 `HMGET`；
- 对账失败（中央不可用）：`backoff` 推进一个短间隔，不改任何副本的 revision；`failOpen=false` 时直接抛异常；
- 为什么不在读路径上逐 key 校验：那样 I/O 次数 = 活跃 key 数 ×（1/`checkInterval`），而批量对账是"每个命名空间每 `checkInterval` 一次"，且能覆盖到冷 key；
- 没有访问就没有对账，也没有定时任务；`version.checkInterval` 是"整个命名空间最大不一致窗口"的上界，不是调度周期。

> **版本表 cleanup 属于存储实现**：`evict` 会 `HDEL` field，删除不留残余；数据 key 因 TTL 自然过期时，`expireAt` 就在 field 的 value 里，存储实现在返回 `revisions(...)` 时顺手 `HDEL` 即可（计数记在存储侧 `deadFieldDropped`）。对编排层来说，这一切都表现为"该 key 不在返回的 map 里"，于是 `HLEN` 收敛到"存活 key 数 + 一个对账周期的滞后"。

> 对账是**抽查**而不是每次读都校验：一个命名空间在 `version.checkInterval` 内只付一次（分批的）中央往返，其余访问全速读本地。`STATIC` / `DYNAMIC` 必须配置正的间隔，不提供"关闭版本机制"的选项，否则长 TTL 配置缓存无法感知变更。

#### 5.1.2 `LocalEntry` 与回填保护

本地存储的 key 就是 `CacheKey`，value 是 `LocalEntry(value, revision, kind)`。**没有 generation，也没有 `LocalKey`**：失效是按 key 覆盖/删除，不存在"整代切换"。

回填必须携带"本次读到的 revision"，且只有现有副本的 revision 不大于它时才覆盖（真值与负副本走同一条路径）：

```java
void backfillLocal(CacheKey ck, LocalEntry<V> entry) {
    if (!local.available()) return;

    local.compute(ck, (cur, ignored) -> {
        // 读取与回填之间已有更新的写落在本地 → 丢弃这次回填，不能把新值盖回旧值
        if (cur != null && cur.revision() > entry.revision()) {
            stats.backfillDiscarded();
            return cur;
        }
        return entry;
    });
}
```

- 竞态出口只有一个：读取与回填之间别的线程写了更新版本。用 revision 比较即可，不需要 CAS 全局代数，也不会把旧值"复活"；
- 版本是**中央时钟时间戳**，不同节点时钟可能不一致。若某节点时钟落后，它的写入可能产生比旧值更小的 revision，此时回填会被丢弃 —— 后果只是"这条 key 在本节点不缓存"（每次读都回中央），**不会返回错值**；
- `DYNAMIC` 的 `put` 仍二次判断热点：非热点 key 不落本地；
- 中央 miss / 负命中时，本地对应副本会被清除（`local.evict`），避免它继续占内存。

### 5.2 写路径

```java
void put(K key, V value, Duration ttl) {
    CacheKey ck = encoder.encode(key);
    Duration effective = ttl != null ? ttl : centralSpec.ttl();

    if (value == null) {
        writeNegativeOrEvict(ck);     // 负缓存关闭时等价于 evict，见 §5.4
        return;
    }

    // 1) 中央优先：值与版本在同一 Lua 内原子完成，返回该 key 的新版本
    long newRev = central.putAndGetRevision(ck, value, effective);

    // 2) 本地：用本次写入的版本覆盖该 key 的副本
    if (local.available()) {
        local.put(ck, new LocalEntry<>(value, newRev), localTtlFor(effective));
    }
}
```

写序理由：

- 中央是权威副本；先写中央，保证其他节点至少能从中央看到新值。
- 返回的新版本直接写进本地副本，因此本节点不会把刚写的值判成过期（不再需要"generation 自失效规避"那一套）。
- 只有本地接受的 key 才写入：`STATIC` 全部接受；`DYNAMIC` **仅已升热的 key** 接受，冷 key 的 `put` 被丢弃并计 `dynamicPutDiscarded`。这是**设计必然**，不是缺陷：热 key 由**读**频次决定（§5.9.1），写不产生频次、也无法保护 L2，所以冷 key 的写没资格占本地内存；代价是这类 key “写后读”会多一次 L2 读（本节点已热的 key 则 read-your-write 正常）。**需要稳定的 read-your-write 就用 `STATIC`。**
- 中央写失败且 `failOpen=true` 时**默认不写本地**（`writeLocalOnCentralFailure=false`），避免本机出现"只有我能看到的新值"。可配置为 `true` 以换取单机读己之写，但必须明确代价。
- `failOpen=false` 时中央写失败直接抛 `CacheException`，本地不更新。
- **版本更新与值写入必须原子**：`SET data` 与 `HSET __rev field` 必须在同一个 Lua 脚本里；否则会出现"值写了、版本没涨"，其他节点永远感知不到这次变更。

### 5.3 失效与清空

```java
void evict(K key) {
    CacheKey ck = encoder.encode(key);

    try {
        central.evict(ck);                 // DEL data + HDEL __rev field，原子（见 CENTRAL §5.3）
    } catch (RuntimeException e) {
        if (!failOpen) throw e;
        stats.centralError();
        // 降级：本机仍要清本地，避免继续返回旧值
    } finally {
        local.evict(ck);                   // 本地按 key 删除，不再依赖 generation
    }
}
```

- **先中央、后本地**：如果先清本地、中央失败，下一次读会把中央旧值回填到本地，等于失效没发生。
- 中央失败且 `failOpen=true` 时，仍清本地；代价是中央旧值可能被下一次读重新回填，直到中央 TTL 到期或下一次成功失效。
- **`evict` 不再返回版本**：field 被 `HDEL` 掉，所以删除对其他节点是"下一轮对账发现中央缺字段" → 清除副本。不需要 tombstone，也不需要"保留版本让它可见"。
- `clear()`：中央 `DEL __rev`（整张版本表）+ `SCAN + UNLINK` 该 namespace 的数据 key；本地 `local.clear()`。版本表被删后，下一轮对账里所有 key 都会"缺字段" → 全部清除。**不再有"先 INCR 一个 namespace 版本号、又要求 SCAN 不能删掉它"这种自相矛盾的规则。**
- `clear` 与版本表删除不构成原子快照：`clear` 之后最多一个对账周期内，本地副本仍可能被命中（要等下次对账才会发现 field 没了）。
- `clear` 期间的并发写可能被随后的 `SCAN + UNLINK` 删掉（包括刚写的数据）—— 后果是**多一次回源**，不会返回错值。
- 负缓存的写入与删除同样是中央写操作，同样更新该 key 的版本；否则其他节点无法感知"从无到有"或"从有到无"。

### 5.4 负缓存

| 场景 | 行为 |
|---|---|
| `centralSpec.nullTtl() == 0`（默认） | 不缓存 null；`put(key, null)` 等价于 `evict(key)` |
| `nullTtl > 0` | `put(key, null)` 写中央负缓存，TTL = `nullTtl` |
| loader 返回 null 且启用负缓存 | 写中央负缓存，后续 `get(key, loader)` 直接返回 `Negative`，不再回源 |
| loader 返回 null 且未启用负缓存 | 返回 `Miss`，下次仍会回源 |
| 本地缓存 | 与中央**同一套语义**：`nullTtl > 0` 时本地同样存"已确认不存在"的负副本（`LocalEntry.kind = NEGATIVE`）；`nullTtl == 0` 时两层都不存 |
| `putIfAbsent(key, null)` | 启用负缓存时，负缓存视为"已存在"，返回 `false`；未启用时等价于 `evict` 后返回 `true`（仍需中央原子性） |
| 版本 | 写负缓存、删除负缓存都更新**该 key** 的版本；否则其他节点无法感知"从无到有"或"从有到无" |
| 关闭负缓存时的空写 | `nullTtl=0` 时 `put(key, null)` 等价 `evict(key)`，但只影响该 key 自己的版本，**不会波及同 namespace 其他 key**（这是 per-key 版本的主要收益） |

负缓存两层都放的理由：

- **只放中央保护不到 L1**：被反复查询的不存在 key（爬虫扫 id、前端轮询未创建的资源）恰恰是最易被打穿的一批。只在中央存负副本，等于把穿透从 DB 转给了 Redis，而 L1 对这类查询完全失效；
- **语义一致**：业务开 `nullTtl` 就是买了"空值也是数据"，L1 不认这个选择等于在本地层废掉它；
- **代价可控**：本地负副本只多一个 `EntryKind.NEGATIVE` 分支，TTL 被 `min(localTtl, nullTtl)` 封顶，不会比中央更久地"确信不存在"；
- **清理免费**：`__rev` 的 field 自带 `expireAt`，对账时会把已过期的死 field 与本地负副本一并清掉，不需要为零副本新增机制。

> **不允许缓存空值的代价**（`nullTtl == 0`，默认）：本库对这个 namespace **放弃防穿透**——loader 返回 null 时什么都不写，`Miss` 会一次次重现，每次都打到数据源。这不是"更安全的默认"，而是把责任交给业务：要么开 `nullTtl`（把重复成本变成一次性），要么在业务层做前置校验（布隆过滤器、参数合法性）。用 `nullLoadRatio`（见 §4.6）可以量化这种浪费。

### 5.5 TTL 规则

| 位置 | 规则 |
|---|---|
| 中央 TTL | 必须为正；`null` 表示使用 `CentralCacheSpec.ttl()`；`Duration.ZERO` 仅在 `allowNoExpiry=true` 时表示永不过期，默认拒绝 |
| 本地 STATIC TTL | 必须为正；不允许"永不过期"（本地是副本，必须有兜底过期） |
| 本地 DYNAMIC TTL | 必须为正；实际 TTL = `min(localTtl, hotTtl, 中央 TTL)` |
| 负缓存 TTL | `nullTtl >= 0`；0 表示关闭 |
| 本地负副本 TTL | `min(localTtl, nullTtl)`，必须 `<= nullTtl`；不允许本地比中央更久地"确信不存在" |
| DYNAMIC 负副本 TTL | `min(localTtl, hotTtl, nullTtl)`；否则会出现"热点身份还在、负副本已过期、每次都回中央查一遍"的抖动 |
| 抖动 | 中央写入时按 `ttlJitter`（默认 0.1）加随机抖动，避免集中过期；本地默认不加抖动 |
| 本地 TTL 上限 | 若中央 TTL 有限，则要求 `localTtl <= centralTtl`，否则启动失败；避免本地比中央活得更久 |

`ttl = null` 统一表示"用默认值"，避免调用方传 `Duration.ZERO` 表达"默认"造成歧义。

> 引入版本机制后，**本地 TTL 不再是变更感知手段**，它只承担三件事：回收冷数据、防止本地无限膨胀、在版本校验长时间失败（或 `clear` 后未再被访问）时兜底。变更感知由 `version.checkInterval` 决定。

### 5.6 失败策略

| 故障 | 默认（`failOpen=true`） | `failOpen=false` | 统计 |
|---|---|---|---|
| 本地读异常 | 当作 Miss，继续查中央 | 抛 `CacheException` | `localError`（预留） |
| 中央读异常/超时 | 当作 Miss；有 loader 则回源 | 抛 `CENTRAL_UNAVAILABLE` / `CENTRAL_TIMEOUT` | `centralErrorCount` |
| 对账 I/O 异常 | 保留全部本地副本与其 revision，按 `version.retryBackoff` 推迟下次对账 | 抛异常 | `versionSyncErrorCount` |
| 对账发现版本变化 / 缺字段 / 数据已过期 | 清除该 key 的副本（下次访问回中央）；死 field 顺手 `HDEL` | 同左 | `versionInvalidationCount` / `deadFieldDropped` |
| 中央写异常 | 记日志，不写本地，调用正常返回（业务视为成功） | 抛异常 | `centralErrorCount` |
| 中央 `putIfAbsent` 异常 | 返回 `false`（无法保证原子性） | 抛异常 | `centralErrorCount` |
| 中央 `evict` 异常 | 记日志，仍清本地 | 抛异常 | `centralErrorCount` |
| 热 key 上报/查询异常 | 保持当前热点状态，不降热；缓冲有界 | 抛异常 | `hotSyncErrorCount` |
| 编解码异常 | **总是抛** `CacheException` | 同左 | `codecErrorCount` |
| key 编码异常 | **总是抛** `CacheException` | 同左 | — |
| loader 异常 | 原样抛出；不写缓存 | 同左 | `loadFailureCount` |

设计原则：**缓存是优化，不是正确性来源**。除了"数据损坏"和"编程错误"，基础设施故障默认不应让业务失败；但必须可通过 `failOpen=false` 切换为严格模式。

### 5.7 一致性模型

#### 5.7.1 基本模型

- 中央缓存是缓存数据的权威副本；本地缓存是**版本受控**的副本。
- 中央为**每个 key** 维护版本号（时间戳）；`put` / 成功的 `putIfAbsent` / 负缓存变更会更新该 key 的版本，`evict` 则直接删掉该 field。
- 本地与中央都用 `CacheKey`；本地副本是 `LocalEntry(value, revision, kind)`（`kind` 区分真值 / 已确认不存在）。
- 失效按 key 判定：对账时副本的 `revision` 与中央不一致，或中央已无该 field（删除 / 整表清空）→ 清除该副本；**不存在 namespace 级整代失效**。

#### 5.7.2 不一致窗口

```text
整个命名空间的感知上界 ≈ version.checkInterval + 一次访问的触发延迟
```

- `STATIC`、`DYNAMIC` 都受这个上界约束；一次对账覆盖该命名空间下**全部本地副本**，因此上界与 key 数量无关，只与访问热度有关（没有访问就没有对账）。
- 没有访问的节点不会感知变更，但它也不会返回该 key 的本地值（没有访问就没有返回）。下一次访问时若已过对账点，会先做一次批量对账，再决定返回什么。
- 单节点写后，本节点用返回的新版本直接覆盖本地副本，不存在"自己写、自己失效"。
- 多节点写同一 key：后写中央者胜；其他节点在下一次校验前可能读到旧值，时间上界同上。

#### 5.7.3 为什么是"per-key 版本 + 批量对账"，而不是别的

| 方案 | 问题 |
|---|---|
| 广播失效 | 订阅断线/丢消息会静默漏失效；失败不可预期 |
| 每次读都查版本 | 本地命中退化为多一次中央往返，失去本地缓存意义 |
| namespace 级版本 | 任一 key 变更清空整个本地命名空间 → 高频写缓存命中率归零，且"删一个不存在的 key"也会触发 |
| 本地 key 拼中央版本号 | 版本回退会让旧 key 重新可见 |
| **per-key 版本 + 批量对账** | 拉模型、无广播、无后台线程；失效粒度等于单 key；I/O 是"每命名空间每周期一次"，与活跃 key 数无关 |

#### 5.7.4 边界

- 版本机制只覆盖**经过 cache4j 的写路径**。绕过 cache4j 直接改 Redis，版本表不会更新，其他节点感知不到；这类操作必须配合手动 `clear` 或等待 TTL。
- per-key 版本下，"任一 key 变更清空整个缓存名"的代价消失，高频写缓存也可以用 `STATIC`（一次写只影响那一条副本）。
- `clear()` 与版本表删除不保证原子；其他节点可能在清空完成前读到部分旧值，但每条副本最多 `version.checkInterval`。
- 需要强一致的场景：使用 `NONE` + 短中央 TTL，或直接绕过缓存；不要让 `STATIC` / `DYNAMIC` 承担强一致职责。

#### 5.7.5 对账期间的并发

- 同一命名空间同一时刻只允许一个线程执行对账（CAS）；
- 对账是**逐条清除**（每条要么命中要么被 evict），不存在"正在失效但还能读到"的中间状态，因此**不需要 `version.during`（`BYPASS_LOCAL` / `USE_LOCAL`）之类的开关**；
- 对账期间其他线程继续正常读本地；
- 对账失败不修改任何副本的 revision，只按 `version.retryBackoff` 推迟下次对账，避免中央故障时每个访问都重试打爆连接。

> v1 不做跨节点广播，也不引入 pub/sub。per-key 版本 + 批量对账在失败时是"继续用本地旧值"，行为可预期、可观测。

### 5.8 生命周期与并发

- `TwoLevelCache` 持有一个本地缓存实例与一个中央缓存视图；`close()` 只关闭本地缓存，**不关闭**中央 provider（中央连接可能被多个缓存共享）。
- `TwoLevelCacheManager` 负责持有并关闭 provider / 本地 provider；`close()` 幂等，先关缓存再关 provider。
- 缓存内核**不创建任何定时任务/调度线程**；版本对账信号量（`nextSyncAtNanos`）与热 key 同步点都是状态字段，由访问线程读取和推进。
- 本地实现自己的清理（如 HashMap 的惰性清理、Caffeine 的维护线程）由实现负责；它们不参与版本与热度语义，`close()` 必须停止并释放。
- 所有缓存实例线程安全；`local` 与 `central` 的实现各自保证并发语义。
- 单飞 map 在 `close()` 时清空；未完成的 future 以 `CacheException` 完成，避免等待者永久阻塞。
- 禁止在 `CacheLoader` 内对同一个 `TwoLevelCache` 的同一个 key 递归调用 `get(key, loader)`；文档与测试都要覆盖这个约束。

### 5.9 `DYNAMIC` 跨节点热 key 协调

#### 5.9.1 问题与前提

**前提（频次的定义）**：`DYNAMIC` 统计的是**读频次**——只有读路径上的 `access()` 记账，`put` / `evict` / `clear` / 对账 / 热 key 上报一律不计频。因为 hotkey 机制的唯一目的是**减少对 L2 的读**（把热读挡在 L1）；而写必须穿透到中央（无论本地有没有副本，L2 的写压力都不变），所以**写在这套机制里没有可优化的对象**，也就不该获得升热资格。写还会推进该 key 的版本、导致其他节点的本地副本被对账清除，对本地缓存是**净消耗**。

只看单节点窗口会低估热点：50 个节点每个节点同一窗口 1 万次、本地阈值 2 万次，单节点都不升热，但集群合计 50 万次，从中央看已经是热 key。因此热度判定必须有集群视角。

#### 5.9.2 四条规则

1. **本地升热立即生效**：本地窗口频次达到 `localHotThreshold`，直接升入本地，不等中央确认。
2. **本地不升热 ≠ 不热**：本地未达阈值时，在访问驱动的热同步事件中向中央做补偿查询；中央判定 `HOT` 则升热。
3. **降热只认中央**：本地频次下降、空闲都不构成降热依据；只有中央返回 `COOLING` 才真正降热。
4. **预选阈值必须低于本地升热阈值**：预选（candidate）桶只收"本地看起来还不够热、但集群口径可能已经够热"的 key，门槛为

   ```text
   candidateThreshold U = clamp( ceil(global.hotThreshold / global.clientEstimate),
                                 1, localHotThreshold - 1 )
   ```

   - `clientEstimate`：连接**同一套 Redis** 的 cache4j 客户端实例数（部署方填，默认 1）；
   - 硬约束 `U < localHotThreshold`：若 `U >= L`，任何达到 U 的 key 早已被本地升热，候选桶永远为空，机制失去意义；
   - 取值带宽是 `[U, L)`。均匀分布下全局热 key 的单节点份额是 `global.hotThreshold / clientEstimate`，只有 `U <= 该份额` 才能保证它在每个节点都落进候选桶 → 有人上报 → 中央看到合计 → 判 `HOT`。否则精确复现"50 节点各 1 万次、谁都不热"的原问题；
   - `U`（来自 `global.window`）与 `L`（来自 `dynamic.window`）必须**同窗口口径**，不等时按比例换算。

例外（不算语义降热）：

- value 的 TTL 到期；
- 热点集合容量满时的 LRU 淘汰；
- 缓存关闭 / 实例下线。

#### 5.9.3 访问驱动，不定期上报

热 key 同步复用版本同步的时钟信号，**不做后台定时上报**：

```text
access(key):
    本地频次 +1
    trackPendingDelta(key)          // 只跟踪合法目标（已升热 + 预选）；缓冲有界

    if hotSyncGate.due(now) && hotSyncGate.tryAcquire():
        p = partitionOf(key)                     // encodedKey 的稳定哈希 & (N-1)
        batch = new LinkedHashMap<CacheKey, Long>()

        for k in hotByPartition[p]:              // 该分区内已升热 key
            batch.put(k, takePendingDelta(k))    //   没增量就带 0（纯状态查询）
        for k in candidateByPartition[p]:        // 该分区内的预选热 key
            batch.putIfAbsent(k, takePendingDelta(k))

        if batch.size() > hotReportBatchMax:
            batch = topByDelta(batch, hotReportBatchMax)   // 倾斜保护，记 hotReportDropped

        try:
            reports = coordinator.reportAndStatusAll(namespace, batch)  // 一次 pipeline / Lua
            applyCentralStatus(reports)          // HOT 补偿升热 / COOLING 降热 / 其余不动
            hotSyncGate.reschedule(now)
            stats.hotSync(batch.size())
        catch:
            restorePendingDelta(batch)           // 整批回填，不是单 key
            hotSyncGate.backoff(now, version.retryBackoff())
            stats.hotSyncError()
        finally:
            hotSyncGate.release()
```

- **不是"一次只报一个 key"**：一次访问上报的是"当前 key 所属**分区**的全部已热 key + 预选 key"。若只报当前 key，集群计数会被系统性低估几个数量级（50 节点 × 1000 热 key 场景下约 1000×），`global.hotThreshold` 永远达不到，`coordination=GLOBAL` 会静默退化成纯本地升热；
- 分区由**正在访问的 key** 决定（`partitionOf(key)`），不需要全局游标：热分区自然被高频访问、上报轮次多；冷分区本来就没人访问；
- 分区函数用 `encodedKey` 的稳定哈希（`h ^ (h >>> 16)` 再 `& (N-1)`），**禁止** `Object.hashCode()` / identity hash，否则同一 key 在不同节点会落入不同分区；
- `hotByPartition` / `candidateByPartition` 是升热 / 降热 / 淘汰时同步维护的桶（数组长度 = `N`），避免每次上报扫描整个 `hotKeys`；
- `delta = 0` 的 key 仍参与上报：这就是"纯状态查询"，让中央在 `staleWindows` 之后返回 `COOLING`；
- 未被访问的分区不上报：那里的热 key 收不到 `COOLING`，但它们同时也不被访问，且容量满时会被 LRU 优先挤掉；中央的状态是**调用时按时间求值**的，所以不需要游标补偿分区（见 §5.9.7）；
- 一次访问最多触发一次合并 I/O；同一次访问同时触发版本对账与热同步时最多两次（批量 `HMGET` + 热 key pipeline）；`cache4j-central-redis` 可以把两者合并成一个 Lua 作为优化，但不改变 SPI 语义。

#### 5.9.4 中央状态

中央按 namespace 维护窗口计数：

```text
cache4j:{ns}:__hot:w:<windowId>   ZSET   key -> count
cache4j:{ns}:__hot:meta           HASH   key -> {active, hot, lowSince}
```

| 状态 | 条件 | 本地动作 |
|---|---|---|
| `HOT` | 当前 + 上一窗口合计 ≥ `dynamic.global.hotThreshold` | 升热或续期 |
| `ACTIVE` | 有上报但未达全局热阈值 | 保持现状，不降热 |
| `COOLING` | 连续 `dynamic.global.demoteWindows` 个窗口低于 `dynamic.global.coolThreshold`，或超过 `dynamic.global.staleWindows` 个窗口无上报 | **降热** |
| `UNKNOWN` | 中央从未收到该 key 上报 | 保持现状，等待下次上报 |

- `dynamic.global.coolThreshold` 明显低于 `dynamic.global.hotThreshold`（建议 1/4），形成滞回；
- 窗口保留当前与上一窗口，查询时相加，降低边界抖动；
- 旧窗口由 TTL 清理，不需要本地定时任务；
- `reportAndStatus` 用一个 Lua 完成 `ZINCRBY + 读取 + 更新 meta + 计算状态 + 返回`。

#### 5.9.5 版本机制同样作用于 `DYNAMIC`

`DYNAMIC` 的本地值也是中央的副本，因此同样使用 per-key 版本与批量对账：

- 版本变化 → 该 key 的副本被覆盖/清除 → 旧值不再命中；
- **热点集合不清空**：key 可能仍然是热的，只是值需要重新从中央读取；
- 热点 key 的副本被对账清除后，下一次访问重新回填；
- 版本机制是**策略无关的本地值保护层**，`STATIC` 与 `DYNAMIC` 共用。

#### 5.9.6 协调模式

| 模式 | 行为 | 适用 |
|---|---|---|
| `coordination=local` | 单 JVM；本地升降热，不访问中央热度 | 单机、测试 |
| `coordination=global` | 必须提供 `HotKeyCoordinator`；本地只升不降，降热由中央决定 | 多节点生产 |

配置为 `global` 但没有任何协调实现时**启动失败**，不静默退化成单节点模式——否则会重新出现"每个节点都认为自己不热"的问题。

#### 5.9.7 失败与退化

| 场景 | 行为 |
|---|---|
| 上报/查询失败 | 保持当前热点状态，不降热；记录 `hotSyncError` |
| 中央长时间不可用 | 热点集合靠 `maxHotKeys` 与本地 TTL 兜底；期间不降热 |
| 上报缓冲满 | 丢弃低频尾部，记录 `hotReportDropped`；热点 key 的增量优先保留 |
| 分区倾斜 | 单分区 key 数超 `hotReportBatchMax` 时按 pending 增量取 top-N 上报，其余留到下一轮并记 `hotReportDropped` |
| 未访问的分区 | 不做游标补偿：那批 key 本来就不被访问；容量满时被 LRU 优先挤掉，下次真被访问时会重新升热并当场拿到最新状态 |
| 本地升热但中央看不到 | 保持到 `hotTtl` / lease 到期；通过指标暴露上报异常 |

> `DYNAMIC` 的正确性下限：即使热 key 判定完全失效，也只是"本地命中率低"或"本地多占一点内存"，不会返回中央不存在的数据；值的正确性由版本机制保证。

---

## 6. 配置模型

### 6.1 构建入口（设计稿）

```java
LocalCacheSpec localSpec = LocalCacheSpec.staticCache(
        "user:detail", 10_000, Duration.ofMinutes(30),
        VersionSpec.builder()
                .checkInterval(Duration.ofSeconds(1))   // 命名空间级对账间隔
                .jitter(0.1)
                .retryBackoff(Duration.ofMillis(200))
                .reconcileBatchSize(1_000)
                .build());

TwoLevelCache<String, User> cache = TwoLevelCache.<String, User>builder("user:detail")
        .keyEncoder(KeyEncoders.canonical())
        .localStrategy(LocalStrategy.STATIC)
        .local(LocalCaches.caffeine(localSpec))
        .central(redisProvider.create(
                CentralCacheSpec.builder("user:detail")
                        .ttl(Duration.ofMinutes(60))
                        .nullTtl(Duration.ofSeconds(30))
                        .failOpen(true)
                        .build(),
                userCodec))
        .statsEnabled(true)          // false = 全部计数器 no-op，派生量返回 -1
        .build();
```

> 说明：`LocalCacheSpec` / `CentralCacheSpec` 的具体字段见 LOCAL / CENTRAL 文档。**版本参数只存在于 `LocalCacheSpec.version`**，`TwoLevelCache` builder 不重复接收；编排层从本地 spec 读取 `VersionSpec` 并维护命名空间级信号量。`checkInterval` 是**整个命名空间的最大不一致窗口**，不是后台调度周期。

### 6.2 配置校验（启动即失败）

| 规则 | 说明 |
|---|---|
| namespace 非空、1–64、字符集合法、无 `{}` | 见 §4.3.5 |
| `NONE` | 忽略本地容量/TTL/版本校验；若配置了非零本地参数，记 WARN |
| `STATIC` | `maxSize > 0`；`ttl > 0`；`ttl <= central.ttl`（中央有限时）；`version.checkInterval > 0` |
| `DYNAMIC` | `maxSize > 0`；`ttl > 0`；`1 <= dynamic.maxHotKeys <= maxSize`；`1 <= dynamic.localHotThreshold <= 255`；`dynamic.window > 0`；`dynamic.hotTtl > 0` 且 `<= central.ttl`；`dynamic.syncInterval > 0`；`dynamic.cooldown >= 0`；`dynamic.sketchWidth` 为 2 的幂且 `>= 1024`；`dynamic.sketchDepth >= 2` |
| `DYNAMIC` + `coordination=GLOBAL` | 必须提供 `HotKeyCoordinator`；`dynamic.global.window > 0`；`dynamic.global.coolThreshold < dynamic.global.hotThreshold`；`dynamic.global.demoteWindows >= 1`；`dynamic.global.staleWindows >= 1`；**`dynamic.cooldown >= dynamic.global.demoteWindows × dynamic.global.window`** |
| `DYNAMIC` 上报分区 | `dynamic.hotReportPartitions` 为 2 的幂且 `>= 2`；`1 <= dynamic.hotReportBatchMax <= dynamic.maxHotKeys` |
| `DYNAMIC` 预选阈值 | `global.clientEstimate >= 1`；`localHotThreshold >= 2`；派生 `U = clamp(ceil(global.hotThreshold / global.clientEstimate), 1, localHotThreshold - 1)`，clamp 后恒 `< localHotThreshold`；若 clamp **前** `ceil(G / clientEstimate) >= localHotThreshold` → WARN（候选带宽只剩 1，全局机制无额外收益） |
| 中央 TTL | `> 0`，或显式 `allowNoExpiry=true`；`nullTtl >= 0` |
| 空值缓存 | `nullTtl > 0` 时本地负副本 TTL = `min(localTtl, nullTtl)` 且必须 `<= nullTtl`；`nullTtl == 0` 时两层都不得存负副本 |
| 版本机制 | `version.checkInterval > 0`；`0 <= version.jitter <= 0.5`；`version.retryBackoff > 0`；`STATIC` / `DYNAMIC` 不允许关闭 |
| 抖动 | `0 <= ttlJitter <= 0.5` |
| 重复缓存名 | 忽略大小写后唯一；重复直接失败 |

所有校验在构建/启动阶段完成，不允许"第一次访问才发现配置错"。

### 6.3 版本与热 key 配置

| 配置 | 默认 | 作用 |
|---|---:|---|
| `version.checkInterval` | `1s` | 命名空间级对账间隔；决定该命名空间的最大不一致窗口 |
| `version.jitter` | `0.1` | 对账间隔抖动比例，避免多节点同时打中央 |
| `version.retryBackoff` | `200ms` | 对账失败后的退避；退避期间不重复对账 |
| `version.reconcileBatchSize` | `1000` | 一次 `HMGET` 的批量大小（控制单次命令载荷） |
| `dynamic.syncInterval` | `1s` | `DYNAMIC` 热 key 同步点间隔（访问驱动） |
| `dynamic.hotReportPartitions` | `16` | 热 key 上报分区数（2 的幂、≥ 2）；单次上报的 key 数 ≈ `maxHotKeys / N` |
| `dynamic.hotReportBatchMax` | `64` | 单次 `reportAndStatusAll` 的硬上限（≈ 400 次 `redis.call`），防倾斜分区夯住 Redis |
| `dynamic.localHotThreshold` | `5` / 窗口 | 本地升热阈值；达到立即升热 |
| `dynamic.window` | `10s` | 本地频率窗口 |
| `dynamic.maxHotKeys` | `1_000` | 本地热点集合上限 |
| `dynamic.hotTtl` | `60s` | 热点 key 本地最长存活 |
| `dynamic.coordination` | `LOCAL`（单机）/ `GLOBAL`（生产） | 热度协调模式 |
| `dynamic.global.window` | `10s` | 中央热 key 计数窗口 |
| `dynamic.global.hotThreshold` | 按业务压测 | 集群级升热阈值 |
| `dynamic.global.clientEstimate` | `1` | 连接同一套 Redis 的 cache4j 客户端实例数；用于推导预选阈值 `U = ceil(hotThreshold / clientEstimate)`（上限被 `localHotThreshold - 1` 夹住） |
| `dynamic.global.coolThreshold` | `dynamic.global.hotThreshold / 4` | 集群级降热阈值，形成滞回 |
| `dynamic.global.demoteWindows` | `3` | 连续低于冷阈值的窗口数后判 `COOLING` |
| `dynamic.global.staleWindows` | `3` | 超过多少个窗口无上报判 `COOLING` |

---

## 7. 可观测性

### 7.1 指标

按三层分类上报，**不要相加**：

```text
保护漏斗（审计层，只有门面的数）：
  requestCount
   ├─ localHit + localNegativeHit          → L1 保护量
   └─ 触达 L2
       ├─ centralHit + centralNegativeHit  → L2 保护量
       └─ miss → sourceLoadCount（其中 sourceNullCount 是白跑的部分）
```

- **审计层**（回答"缓存有没有用"）：`requestCount`、`localHitCount`、`localNegativeHitCount`、`centralHitCount`、`centralNegativeHitCount`、`missCount`、`sourceLoadCount`、`sourceNullCount`、`loadFailureCount`，以及派生量 `protectionRate` / `localProtectionRate` / `centralProtectionRate` / `valueHitRate` / `negativeRatio` / `nullLoadRatio`。
- **控制层**（回答"参数对不对"）：`hotKeyCount`、`candidateCount`、`promotionCount`、`demotionCount`、`localPromotionCount`、`globalPromotionCount`、`globalDemotionCount`、`hotSyncCount`、`hotSyncErrorCount`、`hotReportDropped`、`hotCompensationQueryCount`、`versionSyncCount`、`versionSyncErrorCount`、`versionInvalidationCount`、`deadFieldDropped`、`backfillDiscardedCount`。
- **诊断层**（回答"哪一层坏了"）：`commandCount`、`storeHitCount`、`storeMissCount`、`centralErrorCount`、`codecErrorCount`、`estimatedSize`。

恒等式（监控侧也应校验）：`1 - protectionRate == (1 - localProtectionRate) × (1 - centralProtectionRate)`。

两条审计禁忌：

1. **不跳节点算术平均**：多实例聚合用 `Σ分子 / Σ分母`，不能用 `Σ rate_i / n`（低流量节点的极端值会污染结果）；
2. **不跨服务横向比**：`hitRate` 与访问分布强相关（热点集中 95%、长尾 30% 都很正常），只能纵向对比（同一服务、改动前后、同一时段）。

框架适配器可把 `CacheStatsSnapshot` 绑定到 Micrometer / Solon 的监控接口，建议指标前缀分层：门面用 `cache4j.*`、存储诊断用 `cache4j.store.*`；核心不依赖任何监控库。

### 7.2 日志

**门面**：`org.slf4j`（`cache4j-core` 唯一的第三方依赖，只引 `slf4j-api`；实现由使用方提供，
Spring Boot 默认 Logback 即可）。统一通过 `CacheLogger` 输出，格式分两类：

#### 一、缓存读写日志：`[主题]-业务操作-[key]-详细信息`

**业务操作只有 `读` 与 `写`**；key 单独成段，用 `namespace#hash`（不打印原始 key）。
内部行为放在"详细信息"里（回填、触发热升…），不再单独占用"子过程"位。

```text
[动态本地缓存]-写-[it:hot:dynamic#25bae46a]-冷key，不落本地，仅写中央
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-冷key，miss，硬读中央缓存
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-冷key，miss，硬读中央缓存
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-触发热升，估计频次=3，热点集合大小=1
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-热升回填到本地
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-热key，命中
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-热key，miss，硬读中央缓存
[动态本地缓存]-读-[it:hot:dynamic#25bae46a]-回填到本地
[静态本地缓存]-读-[user:detail#38e3649f]-miss，硬读中央缓存
[静态本地缓存]-读-[user:detail#38e3649f]-回填到本地
[静态本地缓存]-写-[user:detail#38e3649f]-写中央并写本地
[统一中央缓存]-写-[user:detail#38e3649f]-本地策略=NONE，仅写中央
```

读路径详情词表：

| 详情 | 含义 |
|---|---|
| `冷key，miss，硬读中央缓存` | `DYNAMIC` 下未升热的 key 缺席，直接读中央（本地不接受回填） |
| `热key，miss，硬读中央缓存` | 热点 key 的本地副本失效（TTL / 容量 / 失效），读中央 |
| `触发热升，估计频次=N，热点集合大小=N` | 本次读触发升热 |
| `热升回填到本地` | 升热后的第一次回填 |
| `回填到本地` | 普通回填（已热、或静态策略） |
| `热key，命中` / `命中` | 本地命中 |
| `冷却期内拒绝升热，估计频次=N，剩余冷却=Nms` | 冷却期挡住升热 |
| `本地已有更新版本，丢弃本次回填，读到的版本=…，本地版本=…` | 回填被更旧的版本挡下 |

写路径详情词表：`写中央并写本地` / `写中央并写本地（空值）` / `冷key，不落本地，仅写中央` /
`本地策略=NONE，仅写中央`；失败用 `中央写失败，按 failOpen 降级（不写本地），原因=…`（WARN 限流）。

#### 二、非读写日志：`[主题]-子过程-详细信息`

装配 / 关闭 / 失效 / 清空 / 对账 / 热key / 编解码 / 降级 / 自检属于这一类：

```text
[缓存装配]-装配-namespace=user:detail，装配完成，strategy=STATIC，centralTtl=PT10M，coordinator=none
[缓存装配]-装配-共装配 2 个缓存：user:detail, product:hot
[统一中央缓存]-对账-namespace=user:detail，对账完成，清除失效副本 1 条
[统一中央缓存]-读-[user:detail#38e3649f]-中央读取失败，按未命中降级，原因=CacheException:central down
[动态本地缓存]-热key-降热，来源=中央 COOLING，key=product:hot#1befe33d，进入冷却=PT30S，热点集合大小=0
[Redis存储]-编解码-反序列化失败，key=user:detail#38e3649f，serializer=StringRedisSerializer
[缓存key]-编码-不支持的 key 类型：java.lang.Object，请实现 KeyWritable 或提供自定义 KeyEncoder
```

#### 主题（组件）固定词表

**主题 = 组件名**，告诉运维"跑到哪个组件了"；**不要用 namespace 当主题**，
具体缓存放在详情里（`namespace=…` 或 `[namespace#hash]`）。

| 主题 | 对应组件 |
|---|---|
| `统一中央缓存` | `DefaultTwoLevelCache`（编排门面：读/写/失效/清空/对账/降级/装配/关闭） |
| `静态本地缓存` | `STATIC` 策略下的本地存储（`HashMapLocalCache` / `CaffeineLocalCache`） |
| `动态本地缓存` | `DynamicLocalCache`（热点门控、升热、冷却、上报） |
| `缓存装配` | `TwoLevelCacheManager` / Spring 配置 |
| `中央缓存` | `RedisCentralCache`（Redis 语义层） |
| `Redis存储` | `SpringRedisValueStore`（适配层，含编解码） |
| `热key协调` | `RedisHotKeyCoordinator` / `InMemoryHotKeyCoordinator` |
| `缓存key` | `KeyEncoders` |
| `Spring缓存桥接` | `SpringCacheAdapter` / `Cache4jCacheErrorHandler` |
| `日志` | `CacheLogger` 自检 |

#### 级别

| 事件 | 级别 | 频率 / 说明 |
|---|---|---|
| 编解码失败、key 编码失败、配置非法 | **ERROR** | 每次都记；数据/编程错误永不降级，配置错误随启动失败 |
| 中央读 / 写失败（降级） | **WARN** | 按 `(主题, 操作, namespace)` 限流 30s |
| 版本对账失败、热 key 上报失败、loader 失败、缺 `dynamic.global` | **WARN** | 限流；含退避与原因 |
| 缓存装配、缓存关闭、provider 关闭 | **INFO** | 每个缓存一行 + 一行汇总 |
| 本地命中 / 未命中 / 回填 / 升热 / 冷却 / 对账轮次 / 上报批次 | **DEBUG** | 每次；即上面"一"类日志 |
| 缓存命中计数 | 不打汇总日志 | 走 `CacheStats` |

参数化日志统一用 `Supplier` 延迟拼接：关闭对应级别时不产生字符串构造开销。

**测试期级别**：各模块的 `src/test/resources/simplelogger.properties` 决定（IDEA 直接跑与
`mvn test` 行为一致）：

- 单元测试模块：`defaultLogLevel=info`（可见装配/关闭，不刷屏）；
- 集成测试模块（`cache4j-spring-redis-test`）：整体 `info` + `org.slf4j.simpleLogger.log.com.asialjim.cache4j=debug`
  （既能看到完整链路，又不会被 Lettuce / Netty 的内部 DEBUG 刷屏）。

**绑定缺失自检**：`CacheLogger` 首次初始化时若发现所有级别都不可用（通常是没加 SLF4J 绑定），
会向标准错误打印一次提示，而不是让日志静默消失：

```text
[日志]-自检-未检测到可用的 SLF4J 绑定（或全部级别被关闭），cache4j 日志将被丢弃；请加入 slf4j-simple / logback-classic / log4j-slf4j2-impl 之一，并确认日志级别不高于 INFO
```

key 可能包含手机号、证件号等敏感信息；日志中只打印 `CacheKey#masked()`，默认不打印完整 key。

### 7.3 健康检查

- `CentralCache.ping()` 提供轻量探活；框架适配器可暴露 health indicator。
- `ping` 失败不应让应用启动失败（缓存是优化），但应在健康检查中体现为 `DEGRADED`。

---

## 8. 测试策略

### 8.1 契约测试（TCK）

为 `LocalCache` 与 `CentralCache` 各提供一套可复用的契约测试：

- 命中 / 未命中 / 负命中；
- `put` / `putIfAbsent` / `evict` / `clear`；
- TTL 到期（使用可注入的 `Clock`，不 `sleep`）；
- 容量淘汰（写入超过 `maxSize` 后旧 key 被淘汰）；
- 并发 `putIfAbsent` 只有一个成功；
- `close()` 幂等；
- 统计：`requestCount == localHit + localNeg + centralHit + centralNeg + miss`（门面）；恒等式 `1 - protectionRate == (1 - localProtectionRate) × (1 - centralProtectionRate)`；存储实现的审计层字段恒为 `0`、`estimatedSize` 恒为 `-1`；`statsEnabled=false` 时派生量返回 `-1`；单飞的 double-check **不增加** `requestCount`；`nullTtl == 0` 时 `*NegativeHitCount ≡ 0`。

### 8.2 Key 编码

- 单射性：不同类型/值编码不同（`42L` vs `"42"` vs `42`）；
- 稳定性：同一输入多次编码一致；
- 顺序无关：`Set` / `Map` 不同迭代顺序编码一致；
- 长度有界：长字符串/长集合触发段哈希或整键哈希；
- Unicode：中文、emoji 的 UTF-8 字节长度正确；
- 不支持类型：抛 `KEY_ENCODE_FAILED`，不静默回退。

### 8.3 策略矩阵

用内存中央缓存 + 可控 loader 覆盖：

| 用例 | NONE | STATIC | DYNAMIC |
|---|---|---|---|
| 本地命中 | 不查本地 | 直接返回 | 仅热点 key |
| 中央命中回填 | 不回填 | 回填 | 热点才回填 |
| 中央未命中回源 | 回源 | 回源并回填 | 回源；热点才回填 |
| 负缓存 | 生效 | 生效 | 生效 |
| 失效 | 只清中央 | 中央删数据 + 更新该 key 版本；本地删该副本 | 同左，热点状态保留 |
| 其他 key 变更 | 无影响 | 不影响本 key 的副本 | 同左 |
| 对账到点 | 无影响 | 一次批量 HMGET，清除不一致副本 | 同左，另加热 key 同步 |
| 未到对账点 | 无影响 | 直接本地命中 | 仅热点 key 本地命中 |
| 中央故障 | 降级/抛错 | 降级/抛错 | 降级/抛错 |

### 8.4 版本对账

- 未到 `version.checkInterval` 时不产生任何中央 I/O（fake central 统计调用次数为 0），本地全速命中；
- 到点后的下一次访问只产生一组（分批的）`HMGET`，取回该命名空间下**全部本地已知 key** 的版本；**不读数据值**；
- 版本不一致 → 清除该副本；下一轮访问回中央取新值；
- 中央缺字段（key 被删 / `clear`）→ 清除该副本；
- field 的 `expireAt` 已过（数据按 TTL 过期但 field 还在）→ 清除副本 + `HDEL` 该 field（`deadFieldDropped` +1）；
- 版本未变 → 副本保留，不产生任何数据回填（省一次反序列化）；
- 写路径返回新版本 → 本地副本立即带新版本，不产生自失效；
- `evict` 后 field 消失 → 其他节点下一轮对账清除副本（删除传播 ≤ 一个对账周期）；
- 对账失败不修改任何副本，按 `retryBackoff` 推迟下次；
- 同一命名空间并发到点，只有一个线程执行对账（CAS）；
- **无饥饿回归**：同一命名空间 10 万 key 高频混访，某个低频 key 的副本也能在下一轮对账被发现（一次对账覆盖全部本地副本）；
- **载荷回归**：本地 `maxSize=10_000`、中央该命名空间 100_000 key 时，一次对账的 field 数应为 10_000（按本地已知 key 取，不是 `HGETALL`）；
- 读取与回填之间版本被推进（另一线程写入）→ 本次回填被丢弃（`backfillDiscarded` +1），不会把旧值盖回；
- `NONE` 不参与版本对账。

### 8.5 动态热键

- 使用 Zipf 分布模拟访问，断言热点集合包含高频 key；
- 断言 `maxHotKeys` 上限不被突破；
- 断言本地降热不会因"本地频次下降/空闲"发生；只有 `COOLING` 才降热；
- 断言 `coordination=global` 时，单节点未达本地阈值但中央判定 `HOT` 的 key 会被补偿升热；
- 断言 `global` 模式缺少 `HotKeyCoordinator` 时启动失败；
- 断言无访问时既不上报也不查询（fake coordinator 调用次数为 0）；
- 断言频率草图内存固定，不随 key 数量增长；
- 断言冷却期内不会反复升降（抖动抑制）。
- **断言一次到点的访问上报整个分区**：fake coordinator 收到的 payload 大小 ≈ `maxHotKeys / hotReportPartitions`（含 `delta=0` 的纯查询），而不是 1 个 key；
- **集群计数用例（回归）**：50 个 fake 节点、单命名空间 1000 个热 key、每节点 10k 访问/s、`syncInterval=1s`，10s 窗口内中央合计计数应至少达到真实值的 1/10（修复前只有 ~1/1000），据此断言 `HOT` 能被触发；
- 断言分区倾斜时单次 payload 不超过 `hotReportBatchMax`，被截断的增量留到下一轮并计 `hotReportDropped`；
- 断言降热时该 key 的 pending 增量与分区桶条目被清空（不会再上报一个已降热的 key）；
- 断言上报失败后整批增量被回填，下一轮继续上报；
- 断言同一 key 在不同节点（相同 `encodedKey`）落入同一分区号。
- 断言派生阈值恒满足 `U < localHotThreshold`（clamp 生效）；且 clamp 前 `G/clientEstimate >= localHotThreshold` 时启动记 WARN；
- **预选闭环用例（回归）**：`G=100k`、`clientEstimate=50` → `U=2k`、`L=10k`；某 key 每节点 3k/窗口（本地不升热，集群合计 150k ≥ G）→ 断言它进入候选桶、被上报、各节点收到 `HOT` 后补偿升热；
- **必要性反例**：把 `clientEstimate` 配成 5（`U=20k > G/50`）→ 断言该 key 不再被任何节点预选、中央看不到合计、集群判不出 `HOT`（验证 "`U <= G/N` 是必要条件"）；
- 断言候选桶满时按草图估计值保留 top-B（不是 FIFO）。

### 8.6 并发与故障注入

- 单飞：N 个线程同时 `get(key, loader)`，loader 只执行一次；
- loader 失败：所有等待者收到同一异常，缓存无写入；
- 中央超时/异常：`failOpen=true` 时业务成功，`failOpen=false` 时抛 `CacheException`；
- 对账进行中，其他线程仍可正常读本地副本（逐条清除，无半失效窗口）；
- 热 key 上报失败不导致降热；
- 编解码失败：抛错，不返回 Miss；
- 关闭时未完成的单飞 future 被异常完成。

### 8.7 集成与架构

- Redis：Testcontainers（standalone；cluster 作为可选 profile）；
- 版本与热 key Lua：断言原子性、返回状态、窗口边界；
- Spring：`ApplicationContextRunner` 验证条件装配、属性绑定、`@Cacheable` 行为；
- Solon：`Solon.start` 验证 `CacheService` 替换、`@Cache` / tags 行为；
- 架构：ArchUnit / `maven-enforcer` 验证核心模块不依赖 Spring / Solon / Caffeine / Jedis / Lettuce / Redisson；
- 性能冒烟：本地命中吞吐、版本同步开销、DYNAMIC 额外开销；不作为 CI 硬门槛。

---

## 9. 演进路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 本文档 + LOCAL / CENTRAL / ADAPTERS 设计 | 设计评审通过 |
| P1 | 父工程 + `core`（含接口、`TwoLevelCache`、版本门控）+ `local`（HashMap）+ `central`（内存） | 契约测试、策略矩阵、单飞、版本机制测试通过 |
| P2 | `local-caffeine` + `central-redis` | Caffeine/Redis 契约测试、版本 Lua、Testcontainers 通过 |
| P2.5 | `DYNAMIC` 跨节点热 key 协调（内存 + Redis 窗口计数） | 50 节点模拟、补偿升热、中央降热测试通过 |
| P3 | `spring` 适配 | 自动装配、`@Cacheable`、错误处理、属性绑定测试通过 |
| P3.5 | `spring-redis` 适配 | 复用 Spring Data Redis 的 `RedisConnectionFactory`，不重复序列化；Boot 自动装配测试通过 |
| P4 | `solon` 适配 | `CacheService`、`@Cache`、tags、插件生命周期测试通过 |
| P5 | 动态策略调优、指标适配、后续方案（pub-sub 加速 / 值压缩） | 压测与线上灰度验证 |

---

## 10. 决策记录

| ID | 决策 | 理由 | 备选 |
|---|---|---|---|
| D1 | 不设独立编排模块，`TwoLevelCache` 放在 `cache4j-core` | `core` 只依赖接口，不依赖实现；`local` / `central` 保持同级且互不依赖；模块数不膨胀 | 新增独立编排模块；或让 `local` 依赖 `central` |
| D2 | key 使用带类型标签的规范编码 | 修复 `42L` / `"42"` 碰撞；拒绝不稳定的 `Object.toString()` | 复用旧库无类型编码 |
| D3 | **空值缓存两层都存**（同一个 `nullTtl` 控制），本地负副本 TTL ≤ `nullTtl` | 业务开 `nullTtl` 就是买了"空值也是数据"；只放中央会让 L1 对最易被打穿的那类查询完全失效（穿透只从 DB 转到 Redis）。用显式 `EntryKind` 而非 null 值表达，不破坏 `Lookup.Hit` 非空不变式 | 负缓存只放中央（原 D3）/ 本地用 null 隐式表达 |
| D4 | 默认 `failOpen=true` | 缓存是优化，基础设施故障不应拖垮业务 | 默认严格 |
| D5 | 用「per-key 时间戳版本 + 命名空间级批量对账」替代跨节点广播失效 | 拉模型失败可退化；失效粒度等于单 key（不再“一写清全 namespace”）；无需维护订阅连接 | 广播失效 / pub-sub / namespace 级版本 |
| D6 | Redis 客户端不进 `cache4j-central-redis` | 该模块只定义 `RedisValueStore<V>` 与版本/热 key 语义；客户端选择交给适配层 | 直接依赖 Lettuce |
| D7 | DYNAMIC 用"频率草图 + 热点集合"包在存储实现外 | Caffeine 不感知热键；装饰器可复用于 Caffeine / HashMap | 改 Caffeine 淘汰策略 |
| D8 | 框架适配独立模块 | 核心零框架依赖；Spring/Solon 版本演进互不影响 | 核心内置 Spring |
| D9 | 本地 TTL 强制为正 | 本地是副本，必须有兜底过期；版本机制不能替代资源回收 | 允许永不过期 |
| D10 | 值编解码为 SPI，核心不带 JSON | 避免绑定 Jackson 版本；由 util4j / 适配层提供 | 核心内置 Jackson |
| D11 | Redis 下行抽象为**泛型** `RedisValueStore<V>`（不是字节命令 SPI） | 语义层不感知任何客户端，也**不碰字节**；Jedis / Lettuce / Redisson / Solon 插件各自实现 | 在 Redis 实现里绑定某个客户端 / 定义字节级 `RedisCommands` |
| D24 | **编解码归适配层**：core / central / central-redis 都不出现 codec | 谁的 I/O 谁关心：只有真正读写 Redis 的模块才知道 serializer；本地与内存实现根本不需要序列化 | 核心提供 `CacheCodec` SPI（原方案，已推翻） |
| D12 | Spring 的 Redis 连接单独放 `cache4j-spring-redis` | 核心与 Redis 语义保持框架/客户端无关；复用 Spring 的连接池、集群、哨兵配置 | 让 `cache4j-central-redis` 依赖 Spring Data Redis |
| D13 | 版本对账由访问触发，信号量属于**命名空间**，一次对账覆盖全部本地副本 | 没有访问就没有 I/O；每命名空间每周期一次 I/O，与活跃 key 数无关；批量对账不会让某个 key 轮不到校验 | 每秒轮询 / 每 key 各自计时 |
| D14 | 本地 key 就是 `CacheKey`，副本只带 `revision`；**取消 `LocalKey` / `generation` / 每副本时间戳** | per-key 版本下失效是“对账比对这一条”，不需要整代切换；也不需要每条副本记时间 | 保留 `LocalKey(CacheKey, generation)` 的整代失效 |
| D15 | 中央写返回**该 key** 的新版本，且与值写入原子完成 | 本地用新版本直接覆盖副本；不会出现"值写了、版本没涨" | 写后单独更新版本 / 让本地重新校验 |
| D16 | `DYNAMIC` 热升本地优先，降热只认中央 | 本地升热要快；集群热度必须由中央裁决，避免 50 节点都认为不热 | 纯本地升降热 / 定时上报 |
| D17 | `DYNAMIC` 热 key 同步同样由访问触发 | 与版本机制共用信号；无访问无 I/O；上报与查询合并为一次 I/O | 定时上报 / 独立后台对账 |
| D18 | 回填携带「读到的 revision」，只有现有副本的 revision 不大于它时才覆盖 | 回填与写并发时，无条件覆盖会把旧值盖回最新副本（旧值复活） | 回填直接覆盖；或回填不写本地 |
| D19 | 版本值 = 中央时钟时间戳（写时取 `max(now, cur + 1)`） | 只要求"每次写产生不同的值"；比较只看不相等，因此不要求跳节点时钟单调，也不需要 epoch 基数 | 自然数计数 + epoch / 随机 prefix |
| D20 | 版本表 = `<prefix>:<namespacePart>:__rev` **HASH**，field = `encodedKey`，value = `<revision>|<expireAtMillis>` | 一个 namespace 只多 1 个 key；`evict` 直接 `HDEL`，删除不留残余；`expireAt` 让 TTL 自然过期的死 field 也能被对账顺手清掉，因此**不需要额外的清理策略** | 每 key 一个 `__rev:<encodedKey>` / 版本塞进 value envelope |
| D21 | 对账用 `HMGET __rev <本地已知 key>`，不用 `HGETALL` | 载荷跟本地 `maxSize` 走，而不是跟中央该命名空间的 key 数走；缺字段天然表示已删除 | `HGETALL` 全量拉 / 逐 key 单查 |
| D22 | 删除 = `DEL data` + `HDEL field`，不保留版本 | 删除天然可被对账观测到（本地有、中央缺字段），不需要 tombstone；`HLEN` 也不会因删除而膨胀 | 保留 field 当 tombstone / 写 DELETED 标记 |
| D23 | **hotkey 频次只统计读**；`put` / `evict` / `clear` / 对账 / 上报均不计频 | hotkey 的唯一目的是减少**对 L2 的读**；写必须穿透中央，本地有无副本都不影响 L2 写压力，写在这套机制里没有可优化的对象。因此 `DYNAMIC` 丢弃冷 key 的写是设计必然，不是缺陷 | 写也计频 / 写时顺便升热 |

---

## 11. 待定问题

1. `cache4j-local` / `cache4j-central` 是否需要 `LocalCacheProvider` 之外的生命周期接口（如显式 `start`），还是只靠构造 + `AutoCloseable`。
2. 是否提供独立的 Redis 客户端适配模块（`cache4j-redis-lettuce` / `cache4j-redis-jedis` / `cache4j-redis-redisson`），还是只保留 `cache4j-spring-redis`；若提供，优先支持哪个客户端。
3. ~~JSON 编解码归属~~（**已结案**）：编解码归适配层（谁的 I/O 谁关心），核心与中央 SPI 都不再出现 codec；由 `cache4j-spring-redis` 等自行决定 serializer。
4. Spring 适配基线：Spring Boot 3.2+ 先做，Boot 4 是否同版本支持。
5. Solon 适配基线：以 `solon-data` 的 `CacheService` 为准（API 自 1.0 稳定）；`@Cache` 注解与 tags 的桥接是否需要完整覆盖；是否对称增加 `cache4j-solon-redis`（复用 Solon 的 Redis 插件）。
6. 是否需要异步 API（`CompletableFuture`）或虚拟线程优化（Java 21 后再评估）。
7. `DYNAMIC` 默认参数需要压测校准：`dynamic.localHotThreshold`、`dynamic.window`、`dynamic.maxHotKeys`、`dynamic.candidateCapacity`、`dynamic.hotReportPartitions`、`dynamic.hotReportBatchMax`、`dynamic.global.hotThreshold`、`dynamic.global.clientEstimate`、`dynamic.global.coolThreshold`、`dynamic.global.demoteWindows`、`dynamic.global.staleWindows`、草图宽度。
8. 是否提供 `cache4j-bom` 统一版本管理。
9. ~~中央版本的 epoch 取法~~（**已结案**）：版本号改为每个 key 的时间戳，不存在"基数回绕"问题，不需要 epoch。
10. ~~副本过校验期时先只取版本~~（**已被对账模型取代**）：对账只取版本、不取数据值；本地未命中时才做一次合并读。剩余可选优化：对账是否拆成多轮以避开带宽尖峰。
11. `DYNAMIC` 本地升热后，是立即在本次访问内上报一次，还是等到下一次热同步点再上报（当前设计为下一次）。
12. ~~是否需要 per-key 版本（v2）~~（**已结案**）：v1 即采用 per-key 时间戳版本。
13. ~~`__rev` HASH 的 field 清理策略~~（**已结案**）：`evict` 直接 `HDEL`；数据按 TTL 过期时靠 field value 里的 `expireAt` 在对账时识别并 `HDEL`。不需要 `HRANDFIELD` 抽样，也不需要整表 TTL（整表过期会造成一次集中回源尖峰）。
14. `cache4j-starter` 的聚合范围：是否默认带上 `cache4j-local-caffeine`、以及是否提供 `central.type=memory` 的默认装配。
15. ~~`version.during` 的落点~~（**已结案**）：对账是逐条清除，不存在半失效窗口，该配置整个删除。
16. ~~热 key 上报闭环~~（**已结案**）：改为按 `partitionOf(当前 key)` 批量上报该分区的已热 + 预选 key（`hotReportPartitions` / `hotReportBatchMax`），失败整批回填；**不做游标补偿分区**——中央状态是调用时按时间求值的，未被访问的分区无需立即降热。
17. ~~指标分层~~（**已结案**）：按审计/控制/诊断三层分组，只有门面记审计层；四格拆分（含 `localNegativeHitCount` / `centralNegativeHitCount`）；派生量改为 `protectionRate` / `localProtectionRate` / `centralProtectionRate` / `valueHitRate` / `negativeRatio` / `nullLoadRatio`；新增 `sourceNullCount` 量化穿透；记账锚点前移修掉单飞重复计数；两条审计禁忌写入 §7.1。
18. ~~`DYNAMIC` 的 `put` 对冷 key 静默丢弃~~（**已结案，记 D23**）：**设计如此，非缺陷**。hotkey 统计的是读频次（目的是减少 L2 读），写不产生频次、也无法保护 L2，所以冷 key 的写没资格占本地内存；`dynamicPutDiscarded` 是这个代价的可观测项。需要 read-your-write 请用 `STATIC`。
19. ~~冷却集合清理依赖 `maintain()`~~（**已结案**）：改为 `access` 路径上读时惰性删 + 每 1024 次摊销清扫一条；**不引入中央侧 `demotedAt`**（补偿升热侧的振荡靠滞回带宽度调，不加参数）；`cooldown >= demoteWindows × global.window` 入启动校验。
20. ~~`CentralCacheSpec.timeout` 是死配置~~（**已结案**）：字段已删除。`cache4j-central` 不持有连接，没有执行超时的位置；超时归客户端 —— Spring 用 `spring.data.redis.timeout`，自实现 `RedisValueStore` 的用户自定；本库只给"200–500ms 快速失败"的建议，不留占位字段。
21. 大命名空间的自适应：本地 `maxSize` 很大（如 10 万）时，单轮对账的 `HMGET` 批次总载荷需要压测；是否让 `checkInterval` 与 `maxSize` 联动（条目越多间隔越长）。
