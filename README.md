# cache4j

面向 Java 17 的**二级缓存库**：本地缓存（进程内）+ 中央缓存（跨进程共享），并把"本地缓存怎么用"抽象成三种策略：

- `NONE`：本地不存任何数据，读请求直接落到中央缓存；中央未命中再由调用方回源。
- `STATIC`：先查本地，命中即返回；未命中查中央并回填本地；本地有容量与 TTL 淘汰。
- `DYNAMIC`：借鉴 JD HotKey 的热点探测思路，按 key 访问频率做**热升热降**，只有热点 key 才进入本地缓存
  （与 JD HotKey 的理念差异见[设计理念](#设计理念)）。

`STATIC` 与 `DYNAMIC` 都接入同一套**版本机制**：中央为**每个 key** 维护一个时间戳版本（存在该缓存名的 `__rev` HASH 里，field 是编码后的 key，value 是 `revision|过期时刻`），本地副本只携带 `(value, revision)`。每个命名空间一个同步信号量：到点后的下一次访问做一次**批量对账**，范围是"与当前访问 key **同模**（hash 取模分区）的那批本地副本"——本地条目装得进一批时则直接全量对账，否则按 `reconcilePartitions`（默认 16）分区、只对账命中分区，避免一次读背上几十次往返。比对时版本不一致或缺字段的副本被清掉，顺手 `HDEL` 已按 TTL 过期的死 field。没有后台定时轮询，也没有 namespace 级整代失效：一个 key 变更只影响它自己的本地副本。

`DYNAMIC` 的热度判定同时看单节点与集群：本地达到阈值立即升热；本地未达阈值时，在同一次访问驱动的同步事件中向中央做补偿查询；降热只认中央 `COOLING`。

## 设计理念

每一处取舍都围绕一句话：**本地缓存不是为了更快，而是为了保护中央存储。**

### 1. 热点"漏判"比"误判"更危险

`DYNAMIC` 的目的不是"尽量多缓存"，而是在内存有限时把内存投给最可能被再访问的那批 key，
最终把热点流量挡在中央缓存之外。两种误判的代价不对称：

| 误判 | 后果 | 可自愈性 |
|---|---|---|
| **多算**（冷 key 升热） | 占掉本地一个 slot，挤掉一个可能更热的 key | 能：不再访问就被 LRU 淘汰 |
| **少算**（热 key 漏判） | 该 key 的流量**全量打到中央缓存**，恰好违背 `DYNAMIC` 的存在意义 | 不能：每次都要多一次中央往返 |

所以本库分两个阶段、用**相反**的宽容度：**候选预选宁多勿少**（Count-Min Sketch 的"只会高估"
在这里是安全方向），**升热判定必须精确**（每 key 16 片滑动窗口 + int 计数）。

### 2. 不需要额外的热点探测中间件

热点探测天然需要"把同一个 key 的所有增量汇总到一处"。常见做法是把这件事交给一个独立中间件
（Worker 集群 + 注册中心）。本库选择复用中央缓存本身：

| 需求 | 中央缓存已经提供的能力 |
|---|---|
| 同一 key 的增量汇总到一处 | 按 key 分片（单实例天然汇聚，集群按 slot） |
| 多节点的时间窗口对齐 | 服务端 `TIME`（Lua 内 `redis.call('TIME')`） |
| 窗口累加与判定的原子性 | Lua 脚本 |
| 窗口过期清理 | 每片 key 的 TTL |

于是"热 key 协调"退化成"缓存读写"，不需要部署、不需要注册发现、不需要长连接。

### 3. 没有后台线程

版本对账、热 key 上报、策略刷新——所有维护动作都挂在**访问路径**上，到点由当前请求顺带完成。
库不创建调度线程、不养连接池、不起定时任务。

代价是"没有流量就没有维护动作"。对缓存来说这等价于"不影响任何人"：没人访问，就没有副本会陈旧。

### 4. 不一致窗口必须可推理

版本是 **key 级**的中央时钟时间戳，比较只看**不相等**——因此不需要时钟同步、不需要 epoch 基数。
本地副本的陈旧上界是一个明确可算的值（`version.checkInterval`），不是"尽力而为"。

### 5. 策略是运维的事，不是发版的事

代码里只声明**意图**（命名空间、初始本地策略、用途说明），容量/过期/热升阈值等参数归**策略仓库**，
可在运行时按 namespace 独立调整。不提供 `reset`：回退就再改一次，审计留痕比"一键还原"更重要。

### 6. 不静默降级

装配失败、声明冲突、策略缺失要么**启动失败**，要么**可见**（结构化日志 + 指标 + 运维视图标记）。
任何"猜一个值继续跑"的路径都被显式排除。

### 与 JD HotKey 的关系，以及理念差异

本库的热点判定借鉴了 [JD HotKey](https://gitee.com/jd-platform-opensource/hotkey) 的滑动窗口思路
（循环数组分片、按 key 分片避免跨节点聚合），但**两者的取舍方向相反**，因为它们服务的目标不同：

| 维度 | JD HotKey | cache4j |
|---|---|---|
| 形态 | 独立探测中间件（Worker + etcd），与缓存在架构上解耦 | 缓存库内建机制，与两级缓存深度耦合 |
| 判定位置 | 只在中间件；客户端只计数上报 | 本地判定立即生效 + 中央做全局确认/补偿 |
| **误差容忍** | **多算不行**：误判成热会触发误限流/熔断，是业务错误 | **少算更糟**：漏判热点会打穿中央缓存，违背设计目的 |
| 统计结构 | 每 key 精确滑动窗口 | CMS 粗筛（宁多勿少）+ 每 key 精确窗口（升热必须准） |
| 变更传播 | 长连接**推送**（10ms 级） | 访问驱动**拉取**（版本对账，秒级） |
| 延迟目标 | 毫秒级，面向秒杀/刷子拦截 | 秒级，面向缓存保护 |
| 额外中间件 | Worker 集群 + etcd | 无，中央缓存兼任聚合与策略分发 |

一句话：**JD HotKey 用热点判定去"限流"，所以怕误杀；我们用热点判定去"选谁进本地"，所以怕漏判。**

核心模块不依赖 Spring、Solon 或任何运行时框架；框架适配放在独立的 `cache4j-spring`、`cache4j-solon` 模块中。

> 当前状态：**P1 + P2 语义层已落地**——`cache4j-core`（契约 + `TwoLevelCache` 编排）、`cache4j-local`（`NONE`/`STATIC`/`DYNAMIC` + 分段 LRU）、`cache4j-central`（内存参考实现 + 热 key 协调）、`cache4j-central-redis`（`RedisValueStore<V>` 下行 SPI + `RedisCentralCache<V>` 语义层 + `RedisHotKeyCoordinator`，**不碰字节**）、`cache4j-spring-redis`（`SpringRedisValueStore<V>`：连接 + key 布局 + envelope + Lua + serializer）、`cache4j-local-caffeine`（Caffeine 存储实现，与 HashMap 跑同一套契约测试）。`cache4j-spring`（Spring Cache 桥接，不依赖 Boot）与 `cache4j-spring-redis-test`（真实 Redis 集成测试）。175 个用例全绿（Redis 不可达时相关用例自动跳过）。日志走 **SLF4J**，格式固定 `[主题]-子过程-具体信息`（见 DESIGN §7.2）。Spring Boot 自动装配、`cache4j-solon`、`cache4j-starter` 尚未开始。完整设计见：
>
> - [总体设计](docs/DESIGN.md)
> - [本地缓存与三种策略](docs/LOCAL.md)
> - [中央缓存与 Redis 适配](docs/CENTRAL.md)
> - [Spring / Solon 适配](docs/ADAPTERS.md)

## 模块

| 模块 | 职责 | 主要依赖 |
|---|---|---|
| `cache4j` | 父工程（`pom` 聚合 + 依赖版本管理） | — |
| `cache4j-core` | 内核：`Cache`、`Lookup`、`CacheKey`、`LocalEntry`、`KeyEncoder`、`CacheLoader`、本地/中央缓存接口与 Spec、`TwoLevelCache` **接口** + `DefaultTwoLevelCache` 默认编排、统计、错误码、`CacheLogger` | `standard4j`、`slf4j-api` |
| `cache4j-local` | 本地缓存实现：`NONE`/`STATIC`/`DYNAMIC` 策略、默认 HashMap 实现（分段 LRU + TTL）、`LocalCacheProvider` | `cache4j-core` |
| `cache4j-local-caffeine` | 基于 Caffeine 的本地存储实现 | `cache4j-local`、Caffeine |
| `cache4j-central` | 中央缓存 **SPI**：`CentralCache` 契约、`CentralCacheSpec`、`CentralCacheProvider`、`HotKeyCoordinator` 内存实现，以及仅供测试/单机的内存参考实现 | `cache4j-core` |
| `cache4j-central-redis` | Redis **语义层 + 泛型下行 SPI**：`RedisValueStore<V>`、`RedisCentralCache<V>`（版本/负缓存/TTL 抖动/对账/热 key 语义）；**不碰字节**，key 布局/envelope/Lua/序列化都归适配层 | `cache4j-central` |
| `cache4j-spring` | **Spring Framework** 集成：Spring Cache 桥接 + Bean 装配（**不依赖 Boot**、不接 Redis） | `cache4j-core`、`cache4j-local`、`cache4j-central`、`spring-context` |
| `cache4j-spring-redis` | 接入 Spring Data Redis：用 `RedisConnectionFactory` 实现 `RedisValueStore<V>`，key 布局 / envelope / Lua / **serializer** 都在这里；底层客户端由用户选择 | `cache4j-spring`、`cache4j-central-redis`、Spring Data Redis |
| `cache4j-solon` | Solon 插件 + `CacheService`（含 tags）桥接 | `cache4j-core`、`cache4j-local`、`cache4j-central`、`solon-data` |
| `cache4j-spring-redis-test` | 集成测试模块：真实 Redis 下验证 Spring + 二级缓存全链路（只含测试） | 上面若干模块 + Lettuce |
| `cache4j-starter` | **聚合/默认装配**：只拉依赖 + 按配置装配默认实现，**不含编排逻辑** | 上面若干模块 |

依赖方向固定为从上到下，`cache4j-core` 不反向依赖任何实现模块，也不引用 Spring / Solon / Caffeine / Redis 客户端。`cache4j-local` 与 `cache4j-central` 是**同级**实现模块，互不依赖；`cache4j-local-*` 依赖 `cache4j-local`，`cache4j-central-*` 依赖 `cache4j-central`。

Redis 方向的三层边界：

| 层 | 模块 | 定义什么 | 不做什么 |
|---|---|---|---|
| 中央缓存 SPI | `cache4j-central` | 中央缓存的读写、TTL、负缓存、版本、热 key 协调、provider 契约 | 不认识 Redis，也不认识任何客户端 |
| Redis 语义层 | `cache4j-central-redis` | `RedisValueStore<V>` 泛型下行 SPI、`RedisCentralCache<V>`（版本 / 负缓存 / TTL / 对账 / 热 key 语义） | 不碰字节，不含 key 布局 / envelope / Lua / serializer，也不依赖任何客户端或 Spring |
| 适配层（真正做 I/O） | `cache4j-spring-redis` | 用 `RedisConnectionFactory` 实现 `RedisValueStore<V>`：连接 + key 布局 + envelope + Lua + serializer | 不复制版本/热 key 语义；不锁定底层客户端 |

`cache4j-central-redis` **只有接口与纯语义**：不依赖 Jedis、Lettuce、Redisson 或 Spring，也不碰字节。`cache4j-spring-redis` 才是真正与 Redis 读写的地方，负责连接、key 布局、envelope、Lua 与 **serializer**；底层用 Jedis、Lettuce 还是其他客户端，由用户在 `spring.data.redis.*` 中自行选择。

所有模块中只有父工程 `cache4j` 是 `pom` 打包；`cache4j-local`、`cache4j-central` 以及各实现/适配模块都是普通 `jar`，不做聚合 `pom`。

## 快速示例（设计稿 API）

```java
// 本地：STATIC 策略（所有读到的 key 都放本地，受 maxSize 与 TTL 约束）
LocalCache<User> local = LocalCaches.create(
        LocalCacheSpec.staticCache("user:detail", 10_000, Duration.ofMinutes(5)));

// 中央：Redis 由适配层装配（cache4j-spring-redis 提供 RedisValueStore<V>）
CentralCache<User> central = provider.create(
        CentralCacheSpec.builder("user:detail")
                .ttl(Duration.ofMinutes(30))
                .nullTtl(Duration.ofSeconds(30))
                .build());

TwoLevelCache<String, User> cache = TwoLevelCache.<String, User>builder("user:detail")
        .local(local)
        .central(central)
        .build();

// 读：命中本地 → 返回；否则读中央并回填；都没有才走 loader（同一 key 的并发回源只执行一次）
Lookup<User> result = cache.lookup("42", id -> userRepository.findById(id));
User user = result.optional().orElse(null);   // 三态：命中 / 已确认不存在 / 未命中
```

`DYNAMIC` 需要 `HotKeyCoordinator`：`cache4j-central` 提供内存参考实现（单机/测试），Redis 实现由适配层给出；
`coordination=GLOBAL` 而缺少协调实现时，启动阶段直接失败（不静默退化成单节点模式）。

## 构建约定

- Maven 多模块，`groupId = com.asialjim`，父工程 `artifactId = cache4j`，版本 `1.0.0-SNAPSHOT`。
- 包前缀 `com.asialjim.cache4j`，子模块按职责分包（如 `com.asialjim.cache4j.local`）。
- Java 基线 17；Lombok 只作为编译期 `provided`，不进入传递依赖。
- 单测 JUnit 5；Redis 集成测试连本机 Redis（默认 `127.0.0.1:6379`），不可达时用 `Assumptions` 自动跳过，因此可以参与普通 `mvn test`。
