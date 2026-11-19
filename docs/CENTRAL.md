# 中央缓存设计（`cache4j-central` / `cache4j-central-redis`）

> 本文是 [总体设计](DESIGN.md) 的展开，只讨论中央缓存（L2）。
>
> **分层边界（谁的 I/O 谁关心 codec）**：
>
> 1. `cache4j-core` 定义通用契约与编排：**不知道编解码这回事**；
> 2. `cache4j-central` 定义**中央缓存 SPI**（读写、TTL、负缓存、版本、热 key 协调、provider）与内存参考实现；内存实现直接持有 `V`，同样不做序列化；
> 3. `cache4j-central-redis` 定义**Redis 语义层 + 泛型下行 SPI**：`RedisCentralCache<V>`（版本、负缓存、TTL 抖动、批量对账、热 key 窗口的**语义**）与 `RedisValueStore<V>`（“存一个 V、给我版本”的抽象）。**它不碰字节**，也不知道 key 布局、envelope、Lua、序列化；
> 4. 适配模块（`cache4j-spring-redis`、未来 `cache4j-solon-redis` / `cache4j-spring-redisson` / `cache4j-solon-redisson`）才是**真正与 Redis 发生 I/O 的地方**：它们实现 `RedisValueStore<V>`，自己决定 key 布局、envelope、Lua、连接与序列化（`RedisSerializer` / Redisson `Codec` / 自选）。
>
> 推论：不同适配模块的格式**互不兼容**，同一 key 空间必须统一适配器与 serializer；更换时请 bump `globalPrefix` 或清空缓存。

---

## 1. 中央缓存 SPI（通用契约在 `cache4j-core`，中央侧在 `cache4j-central`）

`Cache`、`Lookup`、`CacheKey` 是 core 的通用契约（**编解码不在其中**，归适配层）；`CentralCache`、`CentralCacheSpec`、`CentralCacheProvider` 是中央侧 SPI（接口由 core 暴露），由 `cache4j-central` 负责实现与装配。中央侧 SPI 不认识 Redis，也不认识任何 Redis 客户端。

### 1.1 契约

```java
public interface CentralCache<V> extends Cache<CacheKey, V> {

    /** 批量读；返回顺序与入参一致；单飞/降级由编排层处理 */
    Map<CacheKey, Lookup<V>> lookupAll(Collection<CacheKey> keys);

    /**
     * 一次合并 I/O 返回**该 key** 的版本与值。
     * Redis 实现：`HGET __rev field` + `GET data`（同一 hash tag，一个 Lua / pipeline 合成一次往返）。
     */
    VersionedLookup<V> lookupWithRevision(CacheKey key);

    /** 批量版本化读；每项自带该 key 的 revision */
    Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys);

    /** 指定 key 的当前版本；用于诊断与测试，读路径优先用合并读 */
    long revision(CacheKey key);

    /** 写值并原子更新该 key 的版本，返回新版本 */
    long putAndGetRevision(CacheKey key, V value, Duration ttl);

    /** 原子写负缓存并更新该 key 的版本，返回新版本 */
    long putNegativeAndGetRevision(CacheKey key, Duration ttl);

    /** 成功写入返回新版本；已存在返回 empty，不更新版本 */
    OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl);

    /** 删除数据并删掉该 key 的版本 field（删除对其他节点可见：对账时“中央缺字段”） */
    @Override
    void evict(CacheKey key);

    /**
     * 批量取版本（对账用）：只取版本、不取数据值。
     * 返回 map 中缺失的 key 表示中央已不再持有它（删除 / 清空 / 数据已按 TTL 过期）。
     * 存储实现应在读取时顺手清理自己的过期元数据（如 Redis 的 __rev 死 field），对编排层透明。
     */
    Map<CacheKey, Long> revisions(Collection<CacheKey> keys);

    /** 轻量探活；不抛异常，失败返回 false */
    boolean ping();

    CentralCacheSpec spec();
}
```

```java
public record VersionedLookup<V>(long revision, Lookup<V> lookup) {}

/** 版本 field 的值：revision + 该值的过期时刻（0 = 永不过期） */
public record RevAndExpire(long revision, long expireAtMillis) {}
```

约定：

- `CentralCache` 继承的 `put` / `putIfAbsent` / `evict` / `clear` **必须**复用带版本的方法（`putAndGetRevision` 等）并在内部丢弃返回值；不存在"只写值、不更新版本"的中央写路径。
- 中央缓存实现**不吞异常**：连接失败、命令失败、超时都抛 `CacheException`（`CENTRAL_UNAVAILABLE` / `CENTRAL_TIMEOUT`）。`failOpen` 是编排层（`cache4j-core` 的 `TwoLevelCache`）的策略，不是存储层策略。
- `CentralCacheSpec.failOpen` 只是"该缓存名的默认降级策略"，由编排层读取；单独使用 `CentralCache` 时始终按严格模式处理。
- **版本更新必须与写操作原子完成**：Redis 用 Lua 把 `SET data` 与 `HSET __rev field` 放在一个脚本里；内存实现用锁。
- 写操作返回的新版本是本地更新的依据；实现不得返回旧版本，也不得在写失败时更新版本。
- `putIfAbsent` 未写入时不更新版本；写入成功才更新。
- `clear()` 是两组动作：`DEL __rev`（整张版本表）+ `SCAN + UNLINK` 数据 key；两者不需要保持原子。版本表先删最安全：此后任何访问都会因"版本不存在"而回中央。
- `evict` 不保留版本（`DEL data` + `HDEL field`），因此 `HLEN` 恒等于"存活 key 数 + TTL 已过期但还未被对账清掉的少量滞后"；不需要 `HRANDFIELD` 抽样，也不需要整表 TTL（整表过期会造成一次集中回源尖峰）。
- `close` 只关闭该视图持有的资源；provider 负责连接生命周期，多个 `CentralCache` 共享同一个 provider 时不能互相关闭底层客户端。
- `cache4j-central` 的 `pom.xml` 只有 `cache4j-core` 与 `standard4j`；不得出现 Redis、Spring、Solon 或任何客户端依赖。

```java
public record CentralCacheSpec(
        String namespace,
        Duration ttl,             // 默认 TTL，必须为正
        Duration nullTtl,         // 负缓存 TTL；0 表示关闭
        boolean failOpen,         // 编排层默认降级策略
        double ttlJitter,         // 0–0.5，默认 0.1
        boolean allowNoExpiry     // 默认 false；true 才允许无 TTL
) {}
```

### 1.2 版本号的语义

- 版本号是**每个 key 一个**，取值是"该 key 最后一次写入时的中央时钟时间戳（毫秒）"，只要求"每次写产生不同的值"；
- 写入时用 Lua 取 `newRev = max(now, cur + 1)`：避免同一毫秒内两次写拿到相同值（相等会让其他节点的失配检测失效）；
- 版本号**不要求跨节点单调**：不同节点时钟可能相差数秒，本地判断只看"不相等"（`entry.revision != centralRevision → 失效并回读`），方向无关；
- 因此不需要 epoch、不需要随机前缀、不需要分布式时钟——`__rev` 丢失后重建的版本号天然是更新的时间，不会回到 0；
- 版本号不是缓存值的一部分，不参与 envelope；它存在该 namespace 的 `__rev` HASH 里（field = `encodedKey`，value = `<revision>|<expireAtMillis>`）；
- `expireAtMillis` 是该**数据 key** 的过期时刻（写时 TTL 已知，`0` 表示永不过期）：它的唯一用途是让对账能识别“数据已过期、field 还留着”的死版本并 `HDEL`，所以不需要额外的清理策略。
- 读到比本地更小的版本也不会使旧值复活：本地只会用新值覆盖这条副本；仍建议记 `versionRegression` 指标用于观测时钟异常；
- `evict` **不保留版本**：同一个 Lua 内 `DEL data` + `HDEL field`。删除对其他节点可见，因为对账时会发现“本地有、中央缺字段”。

> 版本号只需要"单调且不重复"，不需要全局有序。不同缓存名的版本互不影响。

### 1.3 `CentralCacheProvider` 与热 key 协调

```java
public interface CentralCacheProvider extends AutoCloseable {

    <V> CentralCache<V> create(CentralCacheSpec spec);

    /** 可选：提供跨节点热 key 协调；没有则 DYNAMIC + GLOBAL 启动失败 */
    default Optional<HotKeyCoordinator> hotKeyCoordinator() {
        return Optional.empty();
    }

    @Override
    void close();
}
```

`HotKeyCoordinator` 是 `cache4j-core` 定义的 SPI，与缓存存储解耦：

```java
public interface HotKeyCoordinator extends AutoCloseable {

    /** 一次 I/O：上报增量 + 查询全局窗口 + 返回状态 */
    HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta);

    /** 批量版本；顺序与入参一致 */
    Map<CacheKey, HotKeyReport> reportAndStatusAll(
            String namespace, Map<CacheKey, Long> deltas);

    @Override
    void close();
}

public record HotKeyReport(
        HotKeyStatus status,     // HOT / ACTIVE / COOLING / UNKNOWN
        long windowCount,        // 当前 + 上一窗口合计
        long lastActiveWindow,
        long lastHotWindow
) {}
```

---

## 2. Redis 值格式（envelope，**适配层职责**）

> envelope 由**适配模块**（如 `cache4j-spring-redis`）定义与实现：`cache4j-central-redis` 只处理 `V`，
> 不知道值在 Redis 里长什么样；`cache4j-central` 连"有没有字节"都不知道。
>
> 下文是一种经过验证的参考格式，供各适配器实现时对齐思路；它**不是**本库的契约。

Redis 里的 value 不是裸的业务字节，而是带元信息的 envelope：

```text
+--------+---------+--------+----------+-------------------+
| magic  | version | flags  | reserved | payload           |
| 1 byte | 1 byte  | 1 byte | 1 byte   | N bytes           |
+--------+---------+--------+----------+-------------------+
  0xC4     0x01      bit0=负缓存            codec 输出
```

- `magic`：0xC4，快速识别"这是 cache4j 的数据"，避免误读其他系统写入的 key；
- `version`：当前 0x01；未来可加压缩、加密、类型信息；
- `flags`：bit0 = 负缓存（payload 为空）；其余位保留；
- `reserved`：0；
- `payload`：适配层自己的 serializer 输出；负缓存时为空。

为什么需要 envelope：

1. **区分"缓存了 null"与"没有缓存"**：Redis 的 `GET` 对两者都返回 nil，必须靠 value 内容区分；
2. **可演进**：版本号、压缩位、未来字段都有位置；
3. **可诊断**：看到 magic 就知道数据来源，减少"这个 key 是谁写的"的排查成本。

约束：

- 解码时校验长度、magic、version；任一不合法抛 `CacheException(CODEC_DECODE_FAILED)`，**不允许**当作 miss；
- `payload` 的大小建议 < 1 MB；超过时记 WARN（压缩列为后续）；
- 更换 codec 会导致旧数据解码失败；上线时配合 key 前缀版本或清空缓存。

---

## 3. Redis key 布局（**适配层职责**）

> key 怎么拼由**适配模块**决定并实现；`cache4j-central-redis` 只传 `CacheKey`（namespace + 规范编码）。
> 下文同样是参考约定，不是契约 —— 但同一 key 空间内必须自洽。

### 3.1 组成

```text
<globalPrefix>:<namespacePart>:<encodedKey>
```

- `globalPrefix`：部署级前缀，默认 `cache4j`；多环境/多租户可设为 `prod:cache4j`；
- `namespacePart`：
  - 默认（`hashTag=true`）：`{<namespace>}`，例如 `{user:detail}`；
  - 关闭 hash tag（`hashTag=false`）：`<namespace 的 UTF-8 字节长度>:<namespace>`，例如 `11:user:detail`；
- `encodedKey`：`KeyEncoder` 的输出（见 DESIGN §4.3）。

示例：

| 配置 | 逻辑 key | Redis key |
|---|---|---|
| `hashTag=true` | `user:detail` + `s2:42` | `cache4j:{user:detail}:s2:42` |
| `hashTag=false` | `user:detail` + `s2:42` | `cache4j:11:user:detail:s2:42` |
| 多环境前缀 | `user:detail` + `s2:42` | `prod:cache4j:{user:detail}:s2:42` |

### 3.2 保留 key

| 用途 | Redis key | 类型 |
|---|---|---|
| key 版本表 | `<prefix>:<namespacePart>:__rev` | hash（field = `encodedKey`，value = `<revision>|<expireAtMillis>`） |
| 热 key 当前窗口 | `<prefix>:<namespacePart>:__hot:w:<windowId>` | zset |
| 热 key 元信息 | `<prefix>:<namespacePart>:__hot:meta` | hash |

保留 key 统一使用 `__` 前缀，避免与 `KeyEncoder` 的输出冲突（`__rev` / `__hot:` 不可能由规范编码产生）。

版本表与数据 key 在同一个 hash tag 下，才能一次往返取回：

```text
HMGET cache4j:{user:detail}:__rev  s2:42
GET   cache4j:{user:detail}:s2:42
# 同一 hash tag，可用 pipeline 或一个 Lua 合成一次网络往返
```

> 为什么版本放 HASH 而不是每条 key 一个 `__rev:<encodedKey>`：一个 namespace 只多 1 个 Redis key（每条多一个会让 key 数量与元数据开销翻倍）；删除/清空都能用 `HDEL` / `DEL` 一次表达；`clear` 也不再需要"先 INCR 版本号、又不能让 SCAN 删掉它"这种自相矛盾的规则。

### 3.3 为什么默认加 hash tag

- Redis Cluster 的多 key 命令（`MGET`、`DEL` 多 key）与多 key Lua 都要求 key 在同一 slot；
- 用缓存名做 hash tag 后，同一个缓存的所有 key 落在同一 slot，`MGET` / 批量删除 / `HMGET`+`GET` 合成一次 Lua 天然可用；
- 热 key 的 ZSET + meta 也在同一 slot，Lua 脚本可以一次操作；
- 代价是热点缓存可能集中在一个 slot；如果某个缓存特别热，可以关闭 hash tag 并接受批量命令退化为逐 key（此时版本与值不再能一次 `MGET`，需要在同一次 pipeline 中发送）。

### 3.4 为什么关闭 hash tag 时要用长度前缀

如果直接拼 `cache4j:user:detail:...`，清理缓存 `user` 时前缀 `cache4j:user:` 会**误伤**缓存 `user:detail`。长度前缀（`4:user` vs `11:user:detail`）从编码上消除了这种前缀包含关系。

默认的 hash tag 形式也有同样保护：`{user}:` 不是 `{user:detail}:` 的前缀，因为 `}` 截断了。

### 3.5 清理前缀

`clear` 使用与写入完全一致的 `namespacePart`：

```text
hashTag=true  → cache4j:{user:detail}:
hashTag=false → cache4j:11:user:detail:
```

前缀必须以 `:` 结尾，且不能只按 namespace 的裸字符串匹配。`clear` 的动作是两步：

```text
1) DEL  <prefix>:__rev                              // 删整张版本表
2) SCAN MATCH <prefix>* COUNT <batch> + UNLINK      // 删数据 key（含 __hot:*）
```

版本表先删最安全：此后任何被访问到的 key 都会因"版本不存在 ≠ 本地副本的 revision"而回中央重取；没被再访问的副本靠本地 TTL 回收。不再有"必须先 INCR 一个 namespace 版本号、又要求清理前缀不能把它删掉"的矛盾。

---

## 4. `RedisValueStore<V>`：泛型下行 SPI（`cache4j-central-redis`）

### 4.1 为什么抽这一层

`cache4j-central-redis` 要回答"版本怎么算、负缓存怎么表达、对账怎么取版本、热 key 窗口怎么判"，
但它**不能接任何 Redis 客户端**，也**不关心值怎么变成字节**。于是把"真正落到 Redis"的那一步抽成
一个**泛型**接口，由适配模块实现：

```java
public interface RedisValueStore<V> extends AutoCloseable {

    /** 读：版本 + 三态。key 不存在 / 已过期 → revision = 0 且 Miss */
    VersionedLookup<V> read(CacheKey key);

    /** 批量读 */
    Map<CacheKey, VersionedLookup<V>> readAll(Collection<CacheKey> keys);

    /** 写值：与版本更新原子完成，返回该 key 的新版本 */
    long write(CacheKey key, V value, Duration ttl);

    /** 写"已确认不存在" */
    long writeNegative(CacheKey key, Duration ttl);

    /** 原子"不存在才写"；已存在返回 empty 且不更新版本 */
    OptionalLong writeIfAbsent(CacheKey key, V value, Duration ttl);

    /** 删除数据并删掉该 key 的版本（删除对其他节点可见：对账时"中央已无此 key"） */
    void delete(CacheKey key);

    /** 清空指定命名空间（含版本元数据） */
    void clear(String namespace);

    /** 批量取版本（对账用）：缺失表示已失效 */
    Map<CacheKey, Long> revisions(Collection<CacheKey> keys);

    /** 指定 key 的当前版本；缺失返回 0 */
    long revision(CacheKey key);

    /** 热 key 上报 + 状态（一次往返） */
    HotKeyReport reportHotKey(String namespace, CacheKey key, long delta);

    /** 批量上报 + 状态 */
    Map<CacheKey, HotKeyReport> reportHotKeyAll(String namespace, Map<CacheKey, Long> deltas);

    /** 轻量探活；失败返回 false，不抛异常 */
    boolean ping();

    @Override
    void close();
}
```

要点：

- **没有 `byte[]`**：值就是 `V`，序列化在实现里完成；
- **没有 key 拼接**：实现只收到 `CacheKey`（namespace + 规范编码）；`clear` 也只收到 namespace；
- **没有客户端类型**：实现必须把客户端异常翻译成 `RedisAccessException`；
- **没有 Lua 约束**：怎么保证"值与版本原子"由实现决定（Lua、事务、`WATCH` 都行）；
- `VersionedLookup` 复用 `cache4j-core` 的定义，上下层对"读到了什么"理解一致。

### 4.2 实现矩阵

| 模块 | 实现 | 依赖 | 说明 |
|---|---|---|---|
| `cache4j-central-redis` | **无**（只有接口与纯语义） | `cache4j-central` | 不依赖任何客户端、不依赖 Spring |
| `cache4j-spring-redis` | `SpringRedisValueStore<V>` | Spring Data Redis + 业务 serializer | 复用 `RedisConnectionFactory`；底层 Jedis / Lettuce 由用户选 |
| 未来 `cache4j-spring-redisson` | `RedissonValueStore<V>` | Redisson + 其 `Codec` | 同一接口，格式独立 |
| 未来 `cache4j-solon-redis` | `SolonRedisValueStore<V>` | Solon 的 Redis 插件 | 同上 |
| `cache4j-central-redis` 单测 | `InMemoryValueStore<V>` | 无 | 内存 Map 实现，**不需要 Redis 容器** |

### 4.3 适配方必须保证的性质

| 性质 | 说明 |
|---|---|
| 版本可用 | 只需保证"每次写产生不同的版本"；比较只用"不相等"，因此时间戳方案（`max(now, cur+1)`）即可，不要求跨节点单调 |
| 删除可观测 | `delete` 之后该 key **不能**再出现在 `revisions(...)` 里 |
| 原子性 | 值写入与版本更新必须原子（Redis 用 Lua，其它客户端用事务/脚本） |
| 过期语义 | 数据按 TTL 过期后，`read` 视为 Miss、`revisions` 视为缺失；版本表自身的死元数据清理也由实现负责 |
| 线程安全 | 必须可被多个缓存视图并发调用 |
| 故障表达 | 统一抛 `RedisAccessException`，由 `RedisCentralCache` 翻译成 `CacheException` |

```java
public class RedisAccessException extends RuntimeException {
    public enum Kind { UNAVAILABLE, TIMEOUT, COMMAND_FAILED }
    private final Kind kind;
}
```

## 5. `RedisCentralCache`

### 5.1 依赖

```java
public final class RedisCentralCache<V> implements CentralCache<V> {

    private final RedisValueStore<V> store;   // 唯一的下行依赖
    private final CentralCacheSpec spec;
    private final CacheCounters stats;
}
```

它只做**语义与策略**：TTL 抖动、负缓存开关、统计、故障翻译。不拼 key、不封 envelope、不写 Lua、
不碰字节 —— 这些全在 `RedisValueStore` 的实现里。

> **单 key 版本读取的场景**：`lookupWithRevision`（本地未命中时读中央）与 `revision(key)`
> （诊断）都是单 key 的；**批量对账走 `revisions(Collection)`**，一次拿回本地全部 key 的版本。
> 两者不要混淆。

### 5.2 读

```java
public VersionedLookup<V> lookupWithRevision(CacheKey key) {
    try {
        return store.read(key);
    } catch (RedisAccessException e) {
        throw translate(e);      // CENTRAL_UNAVAILABLE / CENTRAL_TIMEOUT
    }
}
```

- 版本与值必须来自**同一次**实现内操作（同一 Lua / pipeline），由实现保证；
- 解码失败由实现抛 `CODEC_DECODE_FAILED`，**不允许**当成 miss 静默吞掉；
- 负副本命中按负命中统计；
- 普通 `lookup` / `lookupAll` 复用版本化读取并丢弃版本，v1 不维护第二套逻辑。

### 5.3 写

```java
public long putAndGetRevision(CacheKey key, V value, Duration ttl) {
    if (value == null) {
        return putNegativeAndGetRevision(key, ttl);
    }
    return store.write(key, value, effectiveTtl(ttl != null ? ttl : spec.ttl()));
}

public long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
    if (!spec.nullCachingEnabled()) {
        store.delete(key);                 // 关闭空值缓存时等价于删除
        return 0L;
    }
    return store.writeNegative(key, effectiveTtl(spec.nullTtl()));
}

public void evict(CacheKey key) {
    store.delete(key);                     // 连版本一起删：删除对其他节点可观测
}

public void clear() {
    store.clear(spec.namespace());
}
```

- **TTL 抖动在本层计算**（`ttlJitter`），实现只管按给定 TTL 落盘；
- **版本单调与原子性归实现**：不允许"先写值、再单独涨版本"；
- `putIfAbsent` 走 `store.writeIfAbsent`，未写入不更新版本。

### 5.4 TTL 与抖动

```java
Duration effectiveTtl(Duration ttl) {
    if (ttl == null) {
        ttl = spec.ttl();                 // null 表示“用缓存名默认 TTL”
    }
    if (ttl.isNegative()) {
        throw new CacheException(INVALID_SPEC, "TTL 不能为负");
    }
    if (ttl.isZero()) {
        if (!spec.allowNoExpiry())
            throw new CacheException(INVALID_SPEC, "中央缓存不允许无 TTL");
        return null;                      // 仅显式 allowNoExpiry 时表示永不过期
    }

    long jitter = (long) (ttl.toMillis() * spec.ttlJitter() * (random.nextDouble() * 2 - 1));
    long millis = Math.max(1, ttl.toMillis() + jitter);
    return Duration.ofMillis(millis);
}
```

- 实现使用毫秒精度（Redis 即 `PX`）；小于 1ms 向上取 1ms；
- 抖动默认 ±10%，避免大量 key 同时过期造成回源尖峰；
- 负缓存 TTL 同样抖动；
- "写值与设过期"必须原子，由实现负责（Redis 侧是 `SET ... PX`）。

### 5.5 批量与清理

- `lookupAll` / `lookupAllWithRevision` → `store.readAll(keys)`（实现内部可用 `MGET` / pipeline / Lua 合并往返）；
- `revisions(keys)` → `store.revisions(keys)`（只取版本、不取数据值），供编排层批量对账；
- `clear` → `store.clear()`；**清空的物理方式由实现决定**（Redis 侧是先 `DEL __rev` 再 `SCAN + UNLINK`，非原子）；
- 状态元数据（Redis 的 `__rev` 死 field）清理归实现；对编排层表现为"该 key 不在 `revisions` 返回里"；
- 批量写 v1 仍逐 key，后续可在实现内部合并 pipeline。

### 5.6 故障翻译

`RedisAccessException.Kind` → `CacheException`：

| 实现抛出的 Kind | 翻译 |
|---|---|
| `UNAVAILABLE`（连接拒绝 / 断开 / 集群不可用） | `CACHE-00005 CENTRAL_UNAVAILABLE` |
| `TIMEOUT`（命令超时） | `CACHE-00006 CENTRAL_TIMEOUT` |
| `COMMAND_FAILED`（OOM、READONLY 等） | `CACHE-00005`，message 保留原因 |

序列化失败由**实现**直接抛 `CacheException(CODEC_ENCODE_FAILED / CODEC_DECODE_FAILED)`，
本层原样透传（数据损坏必须可见，永远不降级）。

- 不在 `RedisCentralCache` 内做重试；重试策略由具体客户端 / 连接工厂负责；
- 不做熔断（v1）；需要时在应用层或框架适配层加 Resilience4j 等；
- `failOpen` 的判断在 `TwoLevelCache` 完成。

### 5.7 热 key 协调（`RedisHotKeyCoordinator`）

`cache4j-central-redis` 提供 `HotKeyCoordinator` 的 Redis 实现：它只做**状态机与阈值解析**，
窗口计数怎么存（`ZSET` / `HASH` / pipeline / Lua）由 `RedisValueStore` 的 `reportHotKey*` 实现决定。
阈值来自各缓存的 `dynamic.global.*`（见 LOCAL §2），不放在 `cache4j.redis.*`。

```java
public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
    LocalCacheSpec.GlobalSpec g = globalSpecs.apply(namespace);
    if (g == null) return HotKeyReport.unknown();       // 不猜阈值
    return store.reportHotKey(namespace, key, delta);   // 一次往返：写计数 + 读窗口 + 算状态
}
```

> 下面 5.7.1–5.7.3 的 key 布局与 Lua 是**适配层参考实现**（以 Spring Data Redis + `__rev` HASH 为例），
> 不属于 `cache4j-central-redis` 的代码；换客户端时只需重写 `RedisValueStore`。

#### 5.7.1 key 布局

```text
cache4j:{ns}:__hot:meta                 HASH
  <encodedKey>:active   -> lastActiveWindow
  <encodedKey>:hot      -> lastHotWindow
  <encodedKey>:lowSince -> 连续低活动起始窗口

cache4j:{ns}:__hot:w:<windowId>         ZSET
  member = <encodedKey>
  score  = 该窗口内的集群访问次数
```

- `windowId = floor(now / dynamic.global.window)`；
- 只保留当前与上一窗口，查询时两者相加，降低窗口边界抖动；
- 窗口 key 设置 `2 * dynamic.global.window` 的 TTL，自动清理；
- meta 设置比 `dynamic.global.staleWindows * dynamic.global.window` 更长的 TTL，自动清理长期不活跃的 key。

#### 5.7.2 一次 I/O 完成上报 + 状态

```java
public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
    GlobalSpec g = globalSpecs.apply(namespace);
    if (g == null) {
        // namespace 未配置 dynamic.global：不猜阈值，返回 UNKNOWN 并记 WARN
        // （不计入门面的 hotReportDropped——那是编排层自己的账）
        return HotKeyReport.unknown();
    }
    long windowId = clock.millis() / g.window().toMillis();
    Object r = commands.eval(
            RedisScripts.HOT_REPORT_AND_STATUS,
            List.of(keys.hotMeta(namespace),
                    keys.hotWindow(namespace, windowId),
                    keys.hotWindow(namespace, windowId - 1)),
            args(key.encoded(), delta, windowId,
                 g.hotThreshold(), g.coolThreshold(),
                 g.demoteWindows(), g.staleWindows(),
                 windowTtlMillis(g.window()), metaTtlMillis(g)));
    return decodeReport(r);
}
```

`HOT_REPORT_AND_STATUS`（示意）：

```lua
-- KEYS[1] meta, KEYS[2] current window, KEYS[3] previous window
-- ARGV[1] key, ARGV[2] delta, ARGV[3] windowId,
-- ARGV[4] global hot threshold, ARGV[5] global cool threshold,
-- ARGV[6] demoteWindows, ARGV[7] staleWindows,
-- ARGV[8] windowTtlMillis, ARGV[9] metaTtlMillis

if tonumber(ARGV[2]) > 0 then
  redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[1])
  redis.call('PEXPIRE', KEYS[2], ARGV[8])
end

local cur  = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
local prev = tonumber(redis.call('ZSCORE', KEYS[3], ARGV[1]) or '0')
local total = cur + prev

local active = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':active') or '-1')
local hot    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':hot')    or '-1')
local low    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':lowSince') or '-1')

-- 纯查询且中央从未见过该 key
if tonumber(ARGV[2]) == 0 and active < 0 and total == 0 then
  return { 'UNKNOWN', '0', '-1', '-1' }
end

local status = 'ACTIVE'

-- 长时间无上报：优先判 COOLING
if active >= 0 and tonumber(ARGV[3]) - active >= tonumber(ARGV[7]) then
  status = 'COOLING'
elseif total >= tonumber(ARGV[4]) then
  status = 'HOT'
  hot = tonumber(ARGV[3])
  low = -1
elseif total < tonumber(ARGV[5]) then
  if low < 0 then low = tonumber(ARGV[3]) end
  if tonumber(ARGV[3]) - low >= tonumber(ARGV[6]) then
    status = 'COOLING'
  end
else
  low = -1
end

redis.call('HSET', KEYS[1],
  ARGV[1] .. ':active', ARGV[3],
  ARGV[1] .. ':hot', hot,
  ARGV[1] .. ':lowSince', low)
redis.call('PEXPIRE', KEYS[1], ARGV[9])

return { status, tostring(total), tostring(active), tostring(hot) }
```

- 上报与查询合并为一次 `EVAL`，没有单独的"定时上报"；
- `delta = 0` 时仍然可以查询状态（用于补偿查询）；
- 返回 `HOT` / `ACTIVE` / `COOLING` / `UNKNOWN`；
- `UNKNOWN` 只在"本次 `delta == 0` 且中央从未见过该 key"时返回；任何一次 `delta > 0` 的上报都会让状态进入 `ACTIVE` 或 `HOT`。
- meta 的 TTL 用 `ARGV[9]`，比窗口 TTL 长；窗口 TTL 只清理 ZSET。

#### 5.7.3 批量

`reportAndStatusAll` 一次要处理**一整个分区**的热 key（≈ `maxHotKeys / hotReportPartitions`，硬上限 `hotReportBatchMax = 64`）：

1. **pipeline 逐 key 调用同一个 Lua**（推荐）：每个 key 一次 `EVAL`，N 个 key 合并成一次网络往返；
2. 一个 Lua 遍历 `ARGV` 里的多个 key：一次原子执行，但脚本长度与执行时间随 key 数线性增长。

v1 选 **pipeline**：单个 key 的脚本约 5–6 次 `redis.call`，64 key 一次原子执行会占 Redis 主线程数百微秒；pipeline 把成本摊到多个独立命令上，抖动更小。两种实现都必须：

- 每个 key 的状态计算仍然走同一个 `HOT_REPORT_AND_STATUS` 脚本（**不复制**第二套状态机）；
- 返回值与入参 key 一一对应（缺失项用 `UNKNOWN` 补齐）；
- 单次批量的 key 数不超过 `dynamic.hotReportBatchMax`（由编排层保证，存储层可再校验一次）。

> 为什么不能"一次只上报一个 key"：热 key 判定需要集群合计频次，单 key 上报会让计数被系统性低估（详细推算见 `DESIGN` §5.9.3 与 §8.5 的集群计数用例）。

#### 5.7.4 与版本机制的关系

- 版本同步与热 key 同步是**两条独立的访问驱动信号**；
- 版本同步面向"值是否过期"，热 key 同步面向"热度身份是否变化"；
- 两者可以共用同一次访问，但 SPI 分开，避免把 `STATIC` 也拖进热 key 逻辑；
- `cache4j-central-redis` 可以在内部把"版本 + 值 + 热度"合并成一个 Lua（可选优化），但对外仍暴露独立方法，保持语义清晰。

---

## 6. 适配层怎么写（`RedisValueStore` 的实现者）

### 6.1 `cache4j-central-redis` 不提供客户端

本模块的依赖只有 `cache4j-central`，没有 Jedis / Lettuce / Redisson / Spring Data Redis。它产出的是：

- `RedisValueStore<V>`：泛型下行 SPI（见 §4）；
- `RedisAccessException`：客户端异常的统一翻译目标；
- `RedisCentralCache<V>`：基于 store 的纯语义实现（版本、负缓存、TTL 抖动、对账、统计）；
- `RedisHotKeyCoordinator`：热 key 状态机与阈值解析；
- `RedisCentralCacheProvider`：把 store 装配成 `CentralCache`，并同时提供协调器。

```java
// 适配层提供的 store（Spring 版本见 §7.2），threshold 按缓存名解析
Function<String, LocalCacheSpec.GlobalSpec> globalSpecs = namespace -> specs.globalOf(namespace);

RedisCentralCacheProvider provider =
        new RedisCentralCacheProvider(store, new RedisHotKeyCoordinator(store, globalSpecs));

// DYNAMIC + GLOBAL 时使用；与 CentralCache 共享同一个 store
HotKeyCoordinator coordinator = provider.hotKeyCoordinator().orElseThrow();
```

- `provider.create(spec)` 返回 `RedisCentralCache` 视图；**签名里没有 codec**；
- `provider.hotKeyCoordinator()` 返回构造时传入的协调器，未传入则 `Optional.empty()`；
- 某个 namespace 没有 `GlobalSpec` 时返回 `UNKNOWN` 并记 WARN（不猜阈值）；
- `provider.close()` 关闭 store，实现必须幂等；多个视图共享同一个 store，视图的 `close()` 不关闭 store；
- 谁创建 store，谁负责它的连接配置、key 布局、envelope 与生命周期。

### 6.2 实现者清单

| 要求 | 说明 |
|---|---|
| `read(All)` | 返回版本 + 三态；版本与值同一快照（同一 Lua / pipeline） |
| `write` / `writeNegative` | 值与版本**原子**更新；返回新版本 |
| `writeIfAbsent` | 未写入返回 `empty`，且**不**更新版本 |
| `delete` | 连版本一起删（对账可见） |
| `clear` | 清该 namespace，含状态元数据；物理方式自定 |
| `revisions` | 只取版本；缺失=已失效；顺手清理自己的死元数据 |
| `reportHotKey(All)` | 一次往返：写窗口计数 + 读双窗口 + 返回状态 |
| 序列化 | **自己决定**（Spring `RedisSerializer`、Redisson `Codec`、JSON、JDK…） |
| key 布局 / envelope | **自己决定**，但同一 key 空间内必须自洽 |
| 异常 | 统一 `RedisAccessException(UNAVAILABLE/TIMEOUT/COMMAND_FAILED)`；序列化失败直接抛 `CacheException(CODEC_*)` |
| 超时 | 客户端侧配置；建议缓存命令 200–500ms 快速失败 |
| 线程安全 | 必须可被多个缓存视图并发调用 |

### 6.3 为什么这样切

- `central-redis` 可以在**没有任何 Redis 容器、没有任何客户端**的情况下用 `InMemoryValueStore`
  把版本语义、负缓存、TTL 抖动、批量对账、热 key 状态机全部测透；
- 适配层只实现"怎么和我的客户端说话 + 怎么编码"，**不重复实现版本与热 key 逻辑**（那才是容易写错的部分）；
- 换客户端（Jedis ↔ Lettuce ↔ Redisson ↔ Solon 插件）只换 `RedisValueStore`，语义层不动；
- 避免"缓存库为了支持 Redis，被迫把某个客户端版本带进所有使用方"。

### 6.4 非 Spring 使用

v1 **不提供**独立的 Jedis / Lettuce 适配模块。非 Spring 使用方有两种选择：

1. 自行实现 `RedisValueStore`（接口很小，见 §4.1）；
2. 等待后续按需新增 `cache4j-redis-lettuce` / `cache4j-redis-jedis` / `cache4j-redis-redisson`；
   它们同样只实现 `RedisValueStore`，不会反向进入 `cache4j-central-redis`。

## 7. 与 Spring 的边界（`cache4j-spring-redis`）

### 7.1 原则

- `cache4j-central-redis` **不出现** `org.springframework.*` 依赖，也不出现任何 Redis 客户端依赖；
- Spring 应用的 Redis 连接、池、集群、哨兵、SSL 全部复用 Spring Data Redis 的 `RedisConnectionFactory`；
- `cache4j-spring-redis` 实现 **`RedisValueStore<V>`**：连接适配 + key 布局 + envelope + Lua + **序列化**；
- **serializer 由本模块决定**：默认复用业务提供的 `RedisSerializer<V>`（或按缓存名解析的 `CacheSerializerResolver`），也可以用内置 JDK / JSON 实现；`cache4j-central-redis` 与 `cache4j-core` 都不知情；
- `cache4j-spring-redis` **不锁定底层客户端**：应用使用 Jedis、Lettuce 还是其他实现，由 Spring Data Redis 的依赖与 `spring.data.redis.*` 配置决定；
- **不使用 `RedisTemplate` 的 value serializer 做二次包装**：`RedisConnection` 的 `byte[]` 命令只被调用一次，encode/decode 各自只发生一次（envelope 直接写在字节里）。

### 7.2 `SpringRedisValueStore`

```java
public final class SpringRedisValueStore<V> implements RedisValueStore<V> {

    private final RedisConnectionFactory factory;
    private final RedisKeyFormatter keys;          // 本模块自己的 key 布局
    private final RedisSerializer<V> serializer;   // 本模块自己的编解码方案
    private final LongSupplier milliClock;

    @Override
    public VersionedLookup<V> read(CacheKey key) {
        try (RedisConnection c = factory.getConnection()) {
            // 一个 Lua / pipeline：读 __rev 的 field + 读 data，一次往返
            Object raw = c.scriptingCommands().eval(
                    RedisScripts.READ_WITH_REVISION, ReturnType.MULTI,
                    2, keys.revision(key.namespace()), keys.format(key), ...);
            long revision = decodeRevision(raw);
            byte[] payload = ...;
            if (payload == null) return new VersionedLookup<>(revision, Lookup.miss());
            Envelope env = Envelope.decode(payload);
            return env.negative()
                    ? new VersionedLookup<>(revision, Lookup.negative())
                    : new VersionedLookup<>(revision, Lookup.hit(serializer.deserialize(env.payload())));
        } catch (RuntimeException e) {
            throw translate(e);       // RedisAccessException
        }
    }

    // write / writeNegative / writeIfAbsent / delete / clear / revisions / reportHotKey* 同理
}
```

要点：

- `RedisConnection` 不是线程安全的：每次操作取用、`close()` 归还；连接池/共享原生连接由 Spring 管理；
- `clear` 用 `keyCommands().scan(ScanOptions...)`；cluster 连接需要逐节点处理（v1 标记 best-effort）；
- 底层 `RedisConnectionFailureException` / `RedisSystemException` 统一翻译成 `RedisAccessException`；
- 序列化失败直接抛 `CacheException(CODEC_ENCODE_FAILED / CODEC_DECODE_FAILED)`；
- **envelope 与 key 布局在本模块内实现**：`central-redis` 只看见 `V` 与 `CacheKey`。

### 7.3 自动装配

- `cache4j-spring-redis` 提供 `Cache4jSpringRedisAutoConfiguration`：
  - `@ConditionalOnClass(RedisConnectionFactory.class)`
  - `@ConditionalOnBean(RedisConnectionFactory.class)`
  - `@ConditionalOnProperty(prefix="cache4j.central", name="type", havingValue="redis")`
  - `@AutoConfigureAfter(RedisAutoConfiguration.class)`
- 复用 Spring Boot 的 `spring.data.redis.*`（host/port/password/ssl/cluster/sentinel/pool）；cache4j 只增加 `cache4j.redis.*`（keyPrefix / hashTag / clearBatchSize）；
- 只注册一个 `RedisValueStore` Bean，并据此创建 `RedisCentralCacheProvider`；不创建第二个连接工厂；
- 不覆盖用户自定义的 `RedisConnectionFactory`；
- `cache4j-spring-redis` 只依赖 `spring-data-redis` API，不依赖 starter，也不依赖 Jedis / Lettuce；底层客户端由用户引入；
- 底层客户端由用户选择：应用引入 `spring-boot-starter-data-redis`（默认 Lettuce）或改用 Jedis，`SpringRedisValueStore` 均不感知；
- `cache4j-spring` 不依赖 `cache4j-spring-redis`；后者依赖前者，避免循环。

### 7.4 序列化边界

**谁的 I/O 谁关心 codec。** 真正与 Redis 发生读写的是适配模块，所以序列化也在那里：

| 层 | 负责 | 不负责 |
|---|---|---|
| 业务 | 提供实体与（可选）自己的 `RedisSerializer<V>` | — |
| `cache4j-core` | 编排与契约 | 序列化（完全不知情） |
| `cache4j-central` | 中央 SPI + 内存实现（直接存 `V`） | 序列化 |
| `cache4j-central-redis` | `RedisValueStore<V>` 契约 + 版本/负缓存/TTL/对账/热 key **语义** | key 布局、envelope、Lua、序列化、字节 |
| `cache4j-spring-redis` | 连接 + key 布局 + envelope + Lua + **serializer** | 缓存语义 |
| Spring Data Redis | 连接池/集群/哨兵；客户端（Jedis / Lettuce / 其他）由用户选择 | value 序列化（不做二次包装） |

**兼容性代价（必须知道）**：envelope、key 布局与 serializer 都下沉到适配层之后

- 不同适配模块（`spring-redis` vs `spring-redisson` vs `solon-redis`）的**数据格式互不兼容**；
- 同一个 Redis key 空间必须统一使用同一个适配器 + 同一个 serializer；
- 更换适配器或 serializer ⇒ bump `globalPrefix` 或清空缓存，否则会读不出来（反序列化异常会显式抛 `CODEC_DECODE_FAILED`，不会静默当 miss）。

---

## 8. 内存中央缓存（`cache4j-central`）

`InMemoryCentralCache`：

- `ConcurrentHashMap<CacheKey, Entry>`；
- `Entry` 保存 envelope 的语义（正值/负值）与 `expireAtNanos`；
- 每个 namespace 维护一个 `AtomicLong revision`，与写操作在同一把锁内递增；
- `lookupWithRevision` 在同一锁内读取版本与值，保证快照一致；
- 惰性过期 + 可选定时清理；
- `clear` 按 namespace 清理并递增版本；
- `ping` 恒为 true；
- 附带 `InMemoryHotKeyCoordinator`：窗口计数 + 状态机，供单机 / 测试使用；
- 不跨进程，仅用于：
  - `cache4j-core` 的 `TwoLevelCache` 单元测试；
  - 单机开发/演示；
  - 没有 Redis 时的显式降级（必须配置 `central.type=memory`，不能静默 fallback）。

生产环境必须显式选择 Redis；框架适配器在既没有 Redis 也没有显式 `memory` 配置时，应在启动阶段报配置错误，而不是悄悄用内存缓存。

---

## 9. 运维建议

| 项 | 建议 |
|---|---|
| 内存策略 | 专用 Redis 建议 `allkeys-lru` 或 `allkeys-lfu`；所有 cache key 都有 TTL，`volatile-*` 也可 |
| 持久化 | 缓存实例可关闭 RDB/AOF；开启也不影响 TTL |
| 监控 | `used_memory`、`evicted_keys`、`keyspace_hits/misses`、命令 P99、连接数 |
| 大 key | value > 1MB 记 WARN；避免把大对象整体塞进缓存 |
| 热 key | 监控单 key QPS；必要时用 `DYNAMIC` 本地缓存削峰 |
| 版本表 | `__rev` 是 HASH（field = 编码后的 key，value = `rev|expireAt`）：`evict` 直接 `HDEL`，TTL 过期的死 field 由对账清；监控 `HLEN` 应接近存活 key 数 |
| 热 key 窗口 | `__hot:w:*` 与 `__hot:meta` 有 TTL，允许被淘汰；丢失后状态重新从 `UNKNOWN` 开始 |
| Cluster | 默认 hash tag 保证同缓存同 slot；`clear` 逐节点 SCAN，非原子 |
| 安全 | `rediss://` + ACL；不要用默认密码；key 前缀隔离环境 |
| 版本升级 | codec 变更时 bump `globalPrefix` 或清空；不要假设旧数据可解码 |

---

## 10. 测试

### 10.1 契约测试

同一套 `CentralCache` 契约跑 `InMemoryCentralCache` 与 `RedisCentralCache`：

- `RedisCentralCache` 使用 `InMemoryValueStore`（内存 Map 实现 `RedisValueStore`），**不需要 Redis 容器、不需要任何客户端**；
- 命中 / 未命中 / 负命中；
- `put` / `putIfAbsent` / `evict` / `clear`；
- TTL 到期；
- 批量 `lookupAll` 顺序与缺失；
- `ping`；
- 并发 `putIfAbsent` 只有一个成功；
- 写操作返回该 key 的新版本，且**每次写都不同**（同一毫秒连续两次写也不相等）；
- 不同 key 的版本互不影响（写 A 不改 B 的版本）；
- `putIfAbsent` 未写入时不更新版本；
- `lookupWithRevision` 的版本与值来自同一次往返；
- 版本 field 缺失时返回 `(0, 0)`；`evict` 后该 field 消失（删除对账可见）；
- `clear` 后版本表消失，后续读返回 revision `0`；
- `revisions(keys)` 批量取版本：顺序一致、缺失为 `null`、不拉数据值；

### 10.2 Redis 语义专项（`cache4j-central-redis`，用 `InMemoryValueStore`）

- envelope 黄金字节：magic/version/flags/payload；
- key 布局：hash tag 开/关、globalPrefix、长度前缀、清理前缀不误伤兄弟 namespace；
- 保留 key：`__rev` / `__hot:w:<id>` / `__hot:meta` 不与业务 key 冲突；
- TTL 抖动范围；
- `clear` 只删除本命名空间前缀下的 key（禁止 `KEYS`，用 `SCAN`）；
- 版本 Lua：`SET data` + `HSET field="rev|expireAt"` 原子；失败不更新；返回值为新版本；同一毫秒两次写得到不同值；
- `EVICT` Lua：`DEL data` + `HDEL field` 原子，且**不**留下任何版本痕迹；
- 热 key Lua：上报与状态一次返回；窗口边界（当前 + 上一窗口）；`COOLING` 的两种触发（低活动、长时间无上报）；
- `eval` 返回值映射：整数 → `Long`，字符串 → `byte[]`，数组 → `List`。
- `RedisAccessException.Kind` → `CacheException` 的翻译；
- `InMemoryValueStore` 模拟断连/超时/序列化失败；
- 本模块的 `pom.xml` 架构测试：不得出现 Jedis / Lettuce / Redisson / Spring。

### 10.3 Spring 适配专项（`cache4j-spring-redis`，真实 Redis）

- `RedisConnectionFactory` 存在时自动装配 `SpringRedisValueStore`；
- 不创建第二个连接工厂；
- 不经过 `RedisTemplate` 序列化：用自定义 codec 写入后，直接 `GET` 校验字节格式；
- Spring Data Redis 的 Jedis / Lettuce 连接工厂均可工作；
- `clearByPrefix` 在 standalone / cluster 下的行为（cluster 标记为 best-effort）；
- 连接断开、超时、READONLY 错误的翻译；
- Testcontainers Redis 7，默认不参与普通 `mvn test`。

---

## 11. 扩展点

| 扩展 | 方式 |
|---|---|
| 其他 Redis 客户端（Redisson / Jedis / Solon 插件） | 实现 `RedisValueStore<V>`（自己的 key 布局 / envelope / Lua / serializer），复用 `RedisCentralCache` 语义层 |
| 其他中央存储（Memcached / 对象存储） | 实现 `CentralCache` / `CentralCacheProvider` |
| 压缩 | 在 envelope 的 flags 增加压缩位，`RedisCentralCache` 层处理 |
| 逻辑清空 | per-key 版本 + 批量对账：`clear` 删掉版本表（`DEL __rev`）后，下一轮对账会发现“中央缺字段”并清掉副本 |
| 热 key 协调 | 实现 `HotKeyCoordinator`；Redis 实现位于 `cache4j-central-redis` |
| 指标 | 包装 `CentralCache`，或在框架适配器读取 `CacheStatsSnapshot` |
