# cache4j

面向 Java 17 的**二级缓存库**：本地缓存（进程内）+ 中央缓存（跨进程共享），并把"本地缓存怎么用"抽象成三种策略：

- `NONE`：本地不存任何数据，读请求直接落到中央缓存；中央未命中再由调用方回源。
- `STATIC`：先查本地，命中即返回；未命中查中央并回填本地；本地有容量与 TTL 淘汰。
- `DYNAMIC`：借鉴 JD HotKey 的热点探测，按 key 访问频率做**热升热降**，只有热点 key 才进入本地缓存。

`STATIC` 与 `DYNAMIC` 都接入同一套**版本机制**：中央为**每个 key** 维护一个时间戳版本（存在该缓存名的 `__rev` HASH 里，field 是编码后的 key，value 是 `revision|过期时刻`），本地副本只携带 `(value, revision)`。每个命名空间一个同步信号量：到点后的下一次访问做一次**批量对账**（`HMGET __rev <本地已知 key>`，按批 1000），把整个命名空间的版本一次取回、逐条比对，不一致或缺字段的副本被清掉，顺手 `HDEL` 已按 TTL 过期的死 field。没有后台定时轮询，也没有 namespace 级整代失效：一个 key 变更只影响它自己的本地副本。

`DYNAMIC` 的热度判定同时看单节点与集群：本地达到阈值立即升热；本地未达阈值时，在同一次访问驱动的同步事件中向中央做补偿查询；降热只认中央 `COOLING`。

核心模块不依赖 Spring、Solon 或任何运行时框架；框架适配放在独立的 `cache4j-spring`、`cache4j-solon` 模块中。

> 当前状态：**P1 骨架已落地**——`cache4j-core`（契约 + `TwoLevelCache` 编排）、`cache4j-local`（`NONE`/`STATIC`/`DYNAMIC` + 分段 LRU）、`cache4j-central`（内存参考实现 + 热 key 协调）可编译、73 个测试全绿；Redis 与 Spring / Solon 适配尚未开始。完整设计见：
>
> - [总体设计](docs/DESIGN.md)
> - [本地缓存与三种策略](docs/LOCAL.md)
> - [中央缓存与 Redis 适配](docs/CENTRAL.md)
> - [Spring / Solon 适配](docs/ADAPTERS.md)

## 模块

| 模块 | 职责 | 主要依赖 |
|---|---|---|
| `cache4j` | 父工程（`pom` 聚合 + 依赖版本管理） | — |
| `cache4j-core` | 内核：`Cache`、`Lookup`、`CacheKey`、`LocalEntry`、`KeyEncoder`、`CacheCodec`、`CacheLoader`、本地/中央缓存接口与 Spec、`TwoLevelCache` **接口** + `DefaultTwoLevelCache` 默认编排、统计、错误码 | `standard4j` |
| `cache4j-local` | 本地缓存实现：`NONE`/`STATIC`/`DYNAMIC` 策略、默认 HashMap 实现（分段 LRU + TTL）、`LocalCacheProvider` | `cache4j-core` |
| `cache4j-local-caffeine` | 基于 Caffeine 的本地存储实现 | `cache4j-local`、Caffeine |
| `cache4j-central` | 中央缓存 **SPI**：`CentralCache` 契约、`CentralCacheSpec`、`CentralCacheProvider`、`HotKeyCoordinator` 内存实现，以及仅供测试/单机的内存参考实现 | `cache4j-core` |
| `cache4j-central-redis` | Redis 缓存 **SPI**：`RedisCommands` 命令契约、key 布局、envelope、版本 Lua、热 key 窗口、`RedisCentralCache`；不接入任何 Redis 客户端 | `cache4j-central` |
| `cache4j-spring` | Spring Boot 自动装配 + Spring Cache 桥接（不含 Redis 连接） | `cache4j-core`、`cache4j-local`、`cache4j-central`、Spring |
| `cache4j-spring-redis` | 接入 Spring Data Redis 体系：用 `RedisConnectionFactory` 实现 `RedisCommands`；底层用 Jedis / Lettuce / 其他客户端由用户选择 | `cache4j-spring`、`cache4j-central-redis`、Spring Data Redis |
| `cache4j-solon` | Solon 插件 + `CacheService`（含 tags）桥接 | `cache4j-core`、`cache4j-local`、`cache4j-central`、`solon-data` |
| `cache4j-starter` | **聚合/默认装配**：只拉依赖 + 按配置装配默认实现，**不含编排逻辑** | 上面若干模块 |

依赖方向固定为从上到下，`cache4j-core` 不反向依赖任何实现模块，也不引用 Spring / Solon / Caffeine / Redis 客户端。`cache4j-local` 与 `cache4j-central` 是**同级**实现模块，互不依赖；`cache4j-local-*` 依赖 `cache4j-local`，`cache4j-central-*` 依赖 `cache4j-central`。

Redis 方向的三层边界：

| 层 | 模块 | 定义什么 | 不做什么 |
|---|---|---|---|
| 中央缓存 SPI | `cache4j-central` | 中央缓存的读写、TTL、负缓存、版本、热 key 协调、provider 契约 | 不认识 Redis，也不认识任何客户端 |
| Redis 缓存 SPI | `cache4j-central-redis` | Redis 命令契约 `RedisCommands`、key 布局、envelope、版本/热 key Lua、`RedisCentralCache` | 不依赖 Jedis / Lettuce / Redisson / Spring |
| 框架接入 | `cache4j-spring-redis` | 用 `spring-data-redis` 的 `RedisConnectionFactory` 实现 `RedisCommands` | 不复制 key/envelope/缓存逻辑；不锁定底层客户端 |

`cache4j-central-redis` **只有接口与纯逻辑**：不依赖 Jedis、Lettuce、Redisson 或 Spring。`cache4j-spring-redis` 只负责把 Spring Data Redis 的连接能力接到 `RedisCommands` 上；底层使用 Jedis、Lettuce 还是其他客户端，由用户在 Spring Boot 的 `spring.data.redis.*` 中自行选择。

所有模块中只有父工程 `cache4j` 是 `pom` 打包；`cache4j-local`、`cache4j-central` 以及各实现/适配模块都是普通 `jar`，不做聚合 `pom`。

## 快速示例（设计稿 API）

```java
KeyEncoder keyEncoder = KeyEncoders.canonical();

LocalCache<User> local = LocalCaches.create(
        LocalCaches.caffeineProvider(),
        LocalCacheSpec.staticCache("user:detail", 10_000, Duration.ofMinutes(30))
        // STATIC / DYNAMIC 的 version.checkInterval 由 LocalCacheSpec 携带，默认 1s
);

// redisProvider 由 cache4j-spring-redis 装配（Spring Data Redis），
// 或由业务用自定义 RedisCommands 构造；cache4j-central-redis 本身不带客户端。
CentralCache<User> central = redisProvider.create(
        CentralCacheSpec.builder("user:detail")
                .ttl(Duration.ofMinutes(60))          // 中央 TTL 必须 >= 本地 TTL
                .nullTtl(Duration.ofSeconds(30))
                .build(),
        userCodec
);

TwoLevelCache<String, User> cache = TwoLevelCache.<String, User>builder("user:detail")
        .keyEncoder(keyEncoder)
        .local(local)
        .central(central)
        .build();

Optional<User> user = cache.get("42", id -> userRepository.findById(id));
```

`DYNAMIC` 的集群热 key 协调由 `HotKeyCoordinator` 提供：`cache4j-central` 带内存实现，`cache4j-central-redis` 带 Redis 窗口计数实现，`cache4j-spring-redis` 负责装配；不配置协调实现时，`coordination=global` 会在启动阶段失败。

## 构建约定

- Maven 多模块，`groupId = com.asialjim`，父工程 `artifactId = cache4j`，版本 `1.0.0-SNAPSHOT`。
- 包前缀 `com.asialjim.cache4j`，子模块按职责分包（如 `com.asialjim.cache4j.local`）。
- Java 基线 17；Lombok 只作为编译期 `provided`，不进入传递依赖。
- 单测 JUnit 5；Redis 集成测试使用 Testcontainers，默认不参与普通 `mvn test`。
