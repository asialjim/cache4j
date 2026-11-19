# 框架适配设计（`cache4j-spring` / `cache4j-spring-redis` / `cache4j-solon`）

> 本文是 [总体设计](DESIGN.md) 的展开。核心库与框架无关；本文件只讨论"如何把核心接进 Spring / Solon"。
>
> **硬边界**：
>
> - `cache4j-core` / `local` / `central` / `central-redis` 不出现 Spring、Solon、Jedis、Lettuce、Redisson 依赖；
> - `cache4j-central` 定义中央缓存 SPI，不认识 Redis；
> - `cache4j-central-redis` 定义 Redis 缓存 SPI（`RedisCommands`、key 布局、envelope、`RedisCentralCache`），**没有客户端**；
> - `cache4j-spring-redis` 接入 Spring Data Redis，把 `RedisConnectionFactory` 适配成 `RedisCommands`；底层客户端由用户选择；
> - 适配层不复制缓存逻辑，只做配置绑定、Bean 装配、协议转换、key 转换、错误处理。

---

## 1. 模块职责

```text
cache4j-core（LocalCache / CentralCache 接口 + Spec + TwoLevelCache 接口 + DefaultTwoLevelCache）
├── cache4j-local
│   └── cache4j-local-caffeine   依赖 local
├── cache4j-central              中央缓存 SPI
│   └── cache4j-central-redis    Redis 缓存 SPI（依赖 central，无客户端）
├── cache4j-spring               Spring 装配 + Spring Cache 桥接
│   └── cache4j-spring-redis     依赖 spring + central-redis，接入 spring-data-redis
└── cache4j-solon                Solon 装配 + CacheService 桥接

cache4j-starter                  聚合/默认装配：只拉依赖 + 装配默认实现（不含编排逻辑）
```

> 图中缩进只表示**依赖方向**；Maven 聚合中子模块全部同级，`cache4j-local` / `cache4j-central` 不是 `pom` 聚合模块。

| 模块 | 负责 | 不负责 |
|---|---|---|
| `cache4j-spring` | Spring Cache `CacheManager` / `Cache` 适配、属性绑定、本地缓存与二级缓存的 Bean 装配、错误处理 | Redis 连接、Redis 命令 |
| `cache4j-spring-redis` | 用 `RedisConnectionFactory` 实现 `RedisCommands`；装配 `RedisCentralCacheProvider`；复用 `spring.data.redis.*` | Spring Cache 适配、缓存策略、key/envelope、锁定底层客户端 |
| `cache4j-solon` | Solon `CacheService` / `CacheTagsService` 实现、插件生命周期、属性绑定 | 缓存策略、Redis 连接 |
| `cache4j-starter` | 依赖聚合、默认装配（读 `cache4j.*` 创建默认实现）；Spring 用户另用 `cache4j-spring-boot-starter` | 编排逻辑（在 `cache4j-core`）、框架绑定 |

版本基线（待最终确认）：

| 项 | 基线 | 说明 |
|---|---|---|
| Java | 17 | 与 standard4j 一致 |
| Spring Boot | 3.2+ / Spring Framework 6.1+ | Boot 4 作为后续验证项 |
| Spring Data Redis | 3.x | Jedis / Lettuce 连接工厂均可 |
| Solon | 3.x / 4.x | `solon-data` 的 `CacheService` API 自 1.0 稳定；`CacheTagsService` 自 1.10 |
| JUnit | 5.x | 与 standard4j 一致 |

---

## 2. `cache4j-spring`

### 2.1 自动装配

```java
@AutoConfiguration
@EnableConfigurationProperties(Cache4jProperties.class)
@ConditionalOnProperty(prefix = "cache4j", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class Cache4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocalCacheProvider localCacheProvider(Cache4jProperties props) { ... }

    @Bean
    @ConditionalOnMissingBean
    public TwoLevelCacheManager twoLevelCacheManager(
            LocalCacheProvider localProvider,
            ObjectProvider<CentralCacheProvider> centralProviders,
            Cache4jProperties props) { ... }
}
```

- `Cache4jAutoConfiguration` 负责本地缓存、`TwoLevelCacheManager`、按配置创建命名缓存；
- `CentralCacheProvider` 由 `cache4j-spring-redis`（或用户自定义 Bean）提供；`cache4j-spring` 只按类型注入，不直接依赖 Redis 模块；
- 如果某个缓存配置为 `central.type=redis` 但容器里没有 `CentralCacheProvider`，**启动失败**并给出明确提示；不能静默降级成内存缓存；
- 如果配置 `central.type=memory`，使用 `cache4j-central` 的 `InMemoryCentralCache`，仅用于开发/测试；
- 所有 Bean 都带 `@ConditionalOnMissingBean`，允许业务覆盖。

注册文件：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 2.2 Spring Cache 桥接

```java
public final class SpringCacheManagerAdapter implements org.springframework.cache.CacheManager {

    private final TwoLevelCacheManager delegate;

    @Override
    public org.springframework.cache.Cache getCache(String name) { ... }

    @Override
    public Collection<String> getCacheNames() { ... }
}
```

```java
public final class SpringCacheAdapter implements org.springframework.cache.Cache {

    private final TwoLevelCache<Object, Object> delegate;

    @Override public String getName();
    @Override public Object getNativeCache();  // 返回 TwoLevelCache
    @Override public ValueWrapper get(Object key);
    @Override public <T> T get(Object key, Class<T> type);
    @Override public <T> T get(Object key, Callable<T> valueLoader);
    @Override public void put(Object key, Object value);
    @Override public void evict(Object key);
    @Override public void clear();
    @Override public boolean putIfAbsent(Object key, Object value);
}
```

语义映射：

| Spring | cache4j |
|---|---|
| `get(key)` | `lookup` → `Hit` 返回 `ValueWrapper`；`Negative` / `Miss` 返回 `null` |
| `get(key, type)` | 命中后做类型检查；不匹配抛 `IllegalStateException`（与 Spring 语义一致） |
| `get(key, Callable)` | `get(key, loader)`；loader 的异常包装成 `ValueRetrievalException` |
| `put(key, value)` | `put`；null 按 §5.4 的负缓存规则 |
| `putIfAbsent(key, value)` | `putIfAbsent`；负缓存视为已存在 |
| `evict(key)` | `evict` |
| `clear()` | `clear`；Redis 下是 `SCAN`，非原子 |
| `invalidate()`（若目标 Spring 版本有） | 映射到 `clear()` |
| `getNativeCache()` | 返回 `TwoLevelCache`，便于业务做高级操作 |

`sync=true`：

- Spring 对 `@Cacheable(sync=true)` 会调用 `Cache.get(key, Callable)`；
- 适配器把 `Callable` 包成 `CacheLoader`，交给 `TwoLevelCache` 的单飞；
- 同一个 JVM 内同一个 key 只执行一次 loader；
- 负缓存命中时直接返回 `null`，不调用 `Callable`。

### 2.3 key 转换

Spring 的 key 可能是：

- SpEL 产出的任意对象；
- 多参数方法的 `SimpleKey`；
- `Cache4jKeyGenerator` 产出的字符串。

适配器提供两条路径：

```java
public final class SpringKeyEncoder {

    private final KeyEncoder encoder;

    public CacheKey encode(String cacheName, Object springKey) {
        if (springKey instanceof SimpleKey simpleKey) {
            return new CacheKey(cacheName, encoder.encode(simpleKey.getParams()));
        }
        return new CacheKey(cacheName, encoder.encode(springKey));
    }
}
```

```java
public final class Cache4jKeyGenerator implements KeyGenerator {

    private final KeyEncoder encoder;

    @Override
    public Object generate(Object target, Method method, Object... params) {
        return encoder.encode(params); // 返回 String
    }
}
```

- 推荐显式配置 `Cache4jKeyGenerator`，让未写 `key` 的注解也产出稳定、可读的字符串 key；
- 如果同时使用"不写 key"和"写 key"两种风格，key 形态可能不同（例如 `a1[s2:42]` vs `s2:42`），**同一个缓存名不要混用**；
- `SimpleKey` 必须展开成参数数组，禁止调用 `SimpleKey.toString()`；
- 业务自定义 `KeyGenerator` 优先，适配器只提供兜底。

### 2.4 `CachingConfigurer` 与错误处理

Spring 的 `CachingConfigurer` 全局只能有一个。适配器提供**兜底**配置：

```java
@Bean
@ConditionalOnMissingBean(CachingConfigurer.class)
public CachingConfigurer cachingConfigurer(KeyGenerator keyGenerator,
                                           CacheErrorHandler errorHandler) {
    return new CachingConfigurer() {
        @Override public KeyGenerator keyGenerator() { return keyGenerator; }
        @Override public CacheErrorHandler errorHandler() { return errorHandler; }
    };
}
```

- 业务自己提供 `CachingConfigurer` 时，适配器让位；业务需要自己接上 key generator / error handler；
- `Cache4jCacheErrorHandler`：
  - `failOpen=true`：记录 WARN 并吞掉缓存异常，业务继续；
  - `failOpen=false`：原样抛出；
  - 编解码错误永远不吞（由 `CacheException` 直接抛出）。
- `CacheErrorHandler` 覆盖 `handleCacheGetError` / `handleCachePutError` / `handleCacheEvictError` / `handleCacheClearError`。

### 2.5 值编解码

Spring 的 `@Cacheable` 返回值类型在编译期不一定能映射到某个 `CacheCodec`，因此适配器采用**按缓存名解析 codec**：

```java
public interface CacheCodecResolver {
    <V> CacheCodec<V> resolve(String cacheName);
}
```

- 默认实现：
  1. 查找 `cache4j.caches.<name>.codec` 指定的 Bean 名；
  2. 查找全局 `CacheCodec` Bean；
  3. 仅当显式允许时回退到 `JdkCacheCodec`（要求值实现 `Serializable`）；
  4. 都没有则在启动阶段失败，并提示配置 codec。
- 推荐为每个缓存名提供一个 JSON codec Bean（由 `util4j` 或后续 `cache4j-codec-jackson` 提供）；
- 不把 Spring 的 `RedisTemplate` value serializer 当作 codec；两者职责不同。

### 2.6 配置示例

```yaml
cache4j:
  enabled: true
  default-local-strategy: static
  caches:
    "[user:detail]":
      local-strategy: static
      local:
        provider: caffeine        # caffeine | hashmap
        max-size: 10000
        ttl: 30s
        version:
          check-interval: 1s      # 命名空间级对账间隔；必须为正
          jitter: 0.1
          retry-backoff: 200ms
          reconcile-batch-size: 1000   # 一次 HMGET 的批量大小
      central:
        type: redis               # redis | memory
        ttl: 10m
        null-ttl: 30s
        fail-open: true
      codec: userCacheCodec       # 业务提供的 CacheCodec Bean 名

    "[product:hot]":
      local-strategy: dynamic
      local:
        max-size: 20000
        ttl: 60s
        version:
          check-interval: 1s
        dynamic:
          local-hot-threshold: 100
          window: 10s
          max-hot-keys: 2000
          hot-ttl: 60s
          sync-interval: 1s       # 热 key 同步点间隔（访问驱动）
          cooldown: 30s
          coordination: global    # local | global
          global:
            window: 10s
            hot-threshold: 200000
            cool-threshold: 50000
            demote-windows: 3
            stale-windows: 3
          sketch-width: 4096
          sketch-depth: 4
      central:
        type: redis
        ttl: 10m
      codec: productCacheCodec
  redis:
    key-prefix: cache4j
    hash-tag: true
    clear-batch-size: 500
```

> 缓存名包含 `:` 时，YAML 必须用 `"[user:detail]"` 的括号写法，否则会被当成嵌套层级。

```java
@Configuration
public class CacheCodecConfig {

    @Bean
    public CacheCodec<User> userCacheCodec(ObjectMapper objectMapper) {
        return new JacksonCacheCodec<>(objectMapper, User.class); // 具体实现待定
    }
}
```

### 2.7 生命周期与禁用

- `cache4j.enabled=false` 关闭全部自动装配；
- 业务已定义 `CacheManager` 时，适配器默认让位；可用 `cache4j.spring.primary=true` 强制注册 `@Primary`；
- `TwoLevelCacheManager` 的 `close()` 由 Spring 容器销毁回调触发；先关缓存，再关 provider；
- 不注册任何 `TaskScheduler` / `@Scheduled` 用于版本或热 key 同步；
- 不在启动阶段主动 `ping` Redis；健康检查单独提供。

### 2.8 测试

- `ApplicationContextRunner`：条件装配、属性绑定、缓存名映射；
- `@SpringBootTest` + Testcontainers：`@Cacheable` / `@CachePut` / `@CacheEvict` / `sync=true`；
- 自定义 `CacheManager` 时适配器让位；
- `CachingConfigurer` 唯一性；
- key 生成器与 `SimpleKey` 展开；
- 错误处理：`failOpen=true/false`；
- 版本检查与热 key 同步按访问触发，容器里没有额外调度 Bean；
- 禁用开关。

---

## 3. `cache4j-spring-redis`

### 3.1 职责

只做一件事：把 Spring Data Redis 的连接适配成 `RedisCommands`，让 `cache4j-central-redis` 的 Redis 缓存 SPI 可以工作。

```text
用户选择的底层客户端（Jedis / Lettuce / 其他）
        ↓
Spring Data Redis: RedisConnectionFactory
        ↓
SpringRedisCommands implements RedisCommands   (cache4j-spring-redis)
        ↓
RedisCentralCache                             (cache4j-central-redis)
        ↓
TwoLevelCache                                 (cache4j-core)
```

它**不**实现 key 布局、envelope、负缓存、TTL 抖动、命中统计；这些都在 `cache4j-central-redis` / `cache4j-core`。它也**不**决定底层客户端：Jedis、Lettuce 或其他实现由用户在 Spring Data Redis 侧选择。

### 3.2 `SpringRedisCommands`

```java
public final class SpringRedisCommands implements RedisCommands {

    private final RedisConnectionFactory factory;

    @Override
    public Optional<byte[]> get(byte[] key) {
        try (RedisConnection c = factory.getConnection()) {
            return Optional.ofNullable(c.stringCommands().get(key));
        }
    }

    @Override
    public List<byte[]> getAll(List<byte[]> keys) {
        try (RedisConnection c = factory.getConnection()) {
            return c.stringCommands().mGet(keys.toArray(byte[][]::new));
        }
    }

    @Override
    public void set(byte[] key, byte[] value, Duration ttl) {
        try (RedisConnection c = factory.getConnection()) {
            c.stringCommands().set(key, value,
                    Expiration.milliseconds(ttl.toMillis()),
                    SetOption.upsert());
        }
    }

    @Override
    public boolean setIfAbsent(byte[] key, byte[] value, Duration ttl) {
        try (RedisConnection c = factory.getConnection()) {
            return Boolean.TRUE.equals(c.stringCommands().set(key, value,
                    Expiration.milliseconds(ttl.toMillis()),
                    SetOption.ifAbsent()));
        }
    }

    @Override
    public long del(byte[]... keys) {
        try (RedisConnection c = factory.getConnection()) {
            return c.keyCommands().del(keys);
        }
    }

    @Override
    public boolean expire(byte[] key, Duration ttl) {
        try (RedisConnection c = factory.getConnection()) {
            return Boolean.TRUE.equals(c.keyCommands().pexpire(key, ttl.toMillis()));
        }
    }

    @Override
    public Object eval(byte[] script, List<byte[]> keys, List<byte[]> args) {
        try (RedisConnection c = factory.getConnection()) {
            byte[][] keysAndArgs = concat(keys, args);
            return c.scriptingCommands().eval(
                    script, ReturnType.MULTI, keys.size(), keysAndArgs);
        }
    }

    @Override
    public void clearByPrefix(byte[] prefix, int batchSize) {
        // SCAN MATCH prefix* COUNT batchSize + 分批 DEL/UNLINK
        // cluster 连接需逐节点处理；v1 标记为 best-effort
    }

    @Override
    public boolean ping() {
        try (RedisConnection c = factory.getConnection()) {
            return "PONG".equalsIgnoreCase(c.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

要点：

- `RedisConnection` 不是线程安全的；每次操作取用、`close()` 归还；
- 不缓存 `RedisConnection`，连接池/共享原生连接由 Spring 管理；
- `clearByPrefix` 禁止 `KEYS`；`SCAN` 的 `COUNT` 用 `clearBatchSize`；
- 底层异常统一翻译为 `RedisAccessException`（`UNAVAILABLE` / `TIMEOUT` / `COMMAND_FAILED`）；
- **不使用 `RedisTemplate` 的 value serializer**，直接使用 `RedisConnection` 的 `byte[]` 命令，避免双重序列化；
- 底层客户端由用户选择：Jedis、Lettuce 或其他 Spring Data Redis 支持的实现，适配器都不感知；
- `eval` 使用 `ReturnType.MULTI`，把 Redis 数组映射为 `List<Object>`（元素为 `byte[]` 或 `Long`）；版本 Lua 只返回整数，热 key Lua 返回 `[status, total, active, hot]`，由 `cache4j-central-redis` 统一解码；
- `eval` 不经过 `RedisTemplate.execute(RedisScript)`，避免 `RedisScript` 的结果反序列化器参与。`RedisConnection.scriptingCommands()` 直接返回原始字节。

### 3.3 自动装配

```java
@AutoConfiguration
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnBean(RedisConnectionFactory.class)
@ConditionalOnProperty(prefix = "cache4j.central", name = "type", havingValue = "redis")
@AutoConfigureAfter({RedisAutoConfiguration.class, Cache4jAutoConfiguration.class})
public class Cache4jSpringRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisCommands redisCommands(RedisConnectionFactory factory,
                                       Cache4jRedisProperties props) {
        return new SpringRedisCommands(factory, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public CentralCacheProvider centralCacheProvider(RedisCommands commands,
                                                     Cache4jRedisProperties props) {
        return new RedisCentralCacheProvider(commands, props.keyFormatter());
    }

    @Bean
    @ConditionalOnMissingBean
    public HotKeyCoordinator hotKeyCoordinator(RedisCommands commands,
                                               Cache4jRedisProperties props,
                                               Cache4jProperties cache4jProperties) {
        // 阈值按缓存名解析：各缓存的 local.dynamic.global.* 不同
        return new RedisHotKeyCoordinator(commands, props.keyFormatter(),
                namespace -> cache4jProperties.dynamicGlobalOf(namespace));
    }
}
```

- 复用 Spring Boot 的 `spring.data.redis.*`：host、port、password、database、SSL、sentinel、cluster、pool 全部由 Spring 配置；
- cache4j 只增加 `cache4j.redis.*`：`key-prefix`、`hash-tag`、`clear-batch-size`；
- 热 key 参数来自各缓存的 `local.dynamic.global.*`，不放进 `cache4j.redis.*`；`RedisHotKeyCoordinator` 通过 `namespace -> GlobalSpec` 解析器取值，因此同一个协调器可以服务多个 `DYNAMIC` 缓存；
- 某个 namespace 没有 `dynamic.global` 配置时，协调器拒绝该 namespace 的上报（返回 `UNKNOWN` 并记 WARN），而不是用别的缓存的阈值；
- 不创建第二个 `RedisConnectionFactory`；
- 不覆盖用户自定义的 `RedisConnectionFactory`；
- `cache4j-spring-redis` 的 `pom.xml` 只依赖 `spring-data-redis` 的 API，不依赖 `spring-boot-starter-data-redis`，也不依赖 Jedis / Lettuce；避免替用户锁定客户端；
- 不声明、也不锁定 Jedis / Lettuce 等具体客户端；由用户通过 Spring Data Redis 依赖与配置选择；
- 若容器里已有 `CentralCacheProvider`，自动配置让位；
- 若 `cache4j.central.type=redis` 但类路径没有 Spring Data Redis，启动失败并提示引入依赖。

### 3.4 序列化边界

| 层 | 负责 |
|---|---|
| 业务 | 提供 `CacheCodec<V>` |
| `cache4j-central-redis` | envelope + key 布局 + TTL/负缓存语义 |
| `cache4j-spring-redis` | 连接与命令适配 |
| Spring Data Redis | 连接池/集群/哨兵；客户端（Jedis / Lettuce / 其他）由用户选择；**不**参与 value 序列化 |

### 3.5 集群与清理

- `MGET`：默认 hash tag 保证同一缓存同 slot；
- `SCAN`：standalone / sentinel 直接可用；cluster 需要逐 master 节点，v1 先按 best-effort 实现并在文档标注；
- `clear` 可能较慢；已通过 per-key 版本 + 批量对账做逻辑失效（`DEL __rev` 后下一轮对账会发现“中央缺字段”并清掉副本），物理删除可异步/分批；
- `RedisConnection` 的 cluster 连接需要处理 `MOVED`/`ASK`；Spring Data Redis 已处理重定向，适配器不重复实现；
- `MGET rev key` 依赖同一个 hash tag；关闭 hash tag 时改为同一次 pipeline 发送 `GET rev` + `GET key`。

### 3.6 测试

- Testcontainers Redis 7；
- `RedisConnectionFactory` 自动装配；
- Jedis / Lettuce 两种 Spring 客户端各跑一遍契约测试；
- 字节级校验：自定义 codec 写入后，用原生 `GET` 看 envelope；
- 版本 Lua：写后 `GET __rev` 增加；`MGET rev key` 返回同一快照；
- 热 key Lua：上报 + 状态一次返回；窗口 TTL 生效；
- 连接失败 / 超时 / READONLY 的异常翻译；
- `clearByPrefix` 只删目标前缀；
- cluster 可选 profile（best-effort）。

---

## 4. `cache4j-solon`

### 4.1 Solon 的缓存契约

Solon 的 `solon-data` 提供：

```java
public interface CacheService {
    void store(String key, Object obj, int seconds);
    <T> T get(String key, Type type);
    default <T> T get(String key, Class<T> type);
    void remove(String key);
    default <T> T getOrStore(String key, Type type, int seconds, Supplier<T> supplier);
}

public interface CacheTagsService extends CacheService {
    void storeTag(String key, Object obj, int seconds, String... tags);
    void removeTag(String... tags);
    default <T> T getOrStoreTag(String key, Type type, int seconds,
                                Supplier<T> supplier, String... tags);
}
```

Solon 的 `@Cache` / `@CachePut` / `@CacheRemove` 注解通过 `CacheService` 工作；注册一个 `CacheService` Bean 即可替换默认的 `LocalCacheService`。

### 4.2 `Cache4jCacheService`

```java
public final class Cache4jCacheService implements CacheService {

    private final TwoLevelCache<String, Object> cache; // identity key encoder
    private final CacheCodec<Object> codec;

    @Override
    public void store(String key, Object obj, int seconds) {
        cache.put(key, obj, seconds > 0 ? Duration.ofSeconds(seconds) : null);
    }

    @Override
    public <T> T get(String key, Type type) {
        return cache.get(key)
                .map(v -> cast(v, type))
                .orElse(null);
    }

    @Override
    public void remove(String key) {
        cache.evict(key);
    }

    @Override
    public <T> T getOrStore(String key, Type type, int seconds, Supplier<T> supplier) {
        // 用 TwoLevelCache 的单飞，而不是默认的 get + store
        return cache.get(key, k -> supplier.get())
                .map(v -> cast(v, type))
                .orElse(null);
    }

    public void clear() {
        cache.clear();
    }
}
```

- Solon 的 key 已经是字符串，使用 `KeyEncoders.identity()`；
- `CacheKey.namespace` 默认 `solon`，可配置 `cache4j.solon.cache-name`；
- `seconds <= 0` 使用缓存默认 TTL；不表示"永不过期"；
- `get(key, Type)` 做类型检查；不匹配时记 WARN 并返回 `null`（避免把缓存类型冲突升级成业务异常）；
- `getOrStore` 走单飞，避免同一 key 的并发回源；
- `clear()` 是扩展方法，Solon 的 `CacheService` 接口没有，但便于运维。

### 4.3 Tags

Solon 的 tags 用于批量删除（例如分页缓存）。两种实现方式：

1. **优先复用 Solon 的 `CacheTagsServiceImpl`**：它已经在 `CacheService` 之上实现了 tag 索引（tag → key list）；如果构造器可用，直接 `new CacheTagsServiceImpl(cache4jCacheService)` 并注册为 `CacheTagsService`；
2. 若版本不兼容，在 `cache4j-solon` 内按同样语义实现：
   - `storeTag`：先 `store`，再把 key 加入 tag 列表；
   - `removeTag`：读取 tag 列表，逐个 `remove`，再删除 tag 列表本身；
   - tag 列表本身也存进同一个 `TwoLevelCache`，享受本地/中央两层能力；
   - tag 列表不要无限增长；Solon 官方文档也提示 tags 不能关联太多 key，否则性能会很差。

注册：

```java
@Bean
public CacheService cacheService(...) {
    Cache4jCacheService service = new Cache4jCacheService(...);
    return tagsEnabled ? new CacheTagsServiceImpl(service) : service;
}
```

> `CacheTagsServiceImpl` 的构造器/包名以目标 Solon 版本为准；若不满足，使用上面的自实现。

### 4.4 插件与 Bean 装配

```java
@Configuration
public class Cache4jSolonConfiguration {

    @Bean
    public CacheService cacheService(@Inject("${cache4j.solon}") Properties props) {
        // 读取配置、构建 LocalCache / CentralCache / TwoLevelCache
        return new Cache4jCacheService(...);
    }
}
```

- Solon 的 `@Bean` 注册 `CacheService` 后会替换默认的 `LocalCacheService`；
- 需要生命周期关闭时，实现 `Plugin`（`start` / `stop`）或在 Bean 上声明销毁方法；
- `@Cache` / `@CachePut` / `@CacheRemove` 不需要额外适配：Solon 的注解处理器通过 `CacheService` 调用；
- `@Cache(service="xxx")` 选择多个缓存服务时，为每个 service 注册一个 `CacheService` Bean（Bean 名即 service 名）；
- `tags` 需要注册 `CacheTagsService` 类型，Solon 才会走 tags 分支。

### 4.5 配置示例

```yaml
cache4j:
  solon:
    cache-name: solon
    local-strategy: static       # none | static | dynamic
    local-max-size: 10000
    local-ttl: 30s
    version-check-interval: 1s   # 命名空间级对账间隔（访问驱动）
    central-ttl: 10m
    null-ttl: 30s
    tags: true
    central:
      type: memory               # 没有 Solon Redis 适配时，仅开发/测试
    # dynamic 策略额外配置：local-hot-threshold / hot-window /
    # sync-interval / coordination=global + global.*（见 LOCAL.md）
```

> v1 的 `cache4j-solon` 不提供 Redis 客户端适配。若 Solon 应用需要 Redis，按同样模式新增 `cache4j-solon-redis`：用 Solon 的 Redis 插件实现 `RedisCommands`，再注入 `RedisCentralCacheProvider`。它与 `cache4j-spring-redis` 对称，不把 Solon 依赖带进 `cache4j-central-redis`。

### 4.6 测试

- `Solon.start` 启动，验证 `CacheService` 替换默认实现；
- `@Cache` / `@CachePut` / `@CacheRemove` 行为；
- `getOrStore` 单飞：并发请求只执行一次 supplier；
- `storeTag` / `removeTag` 批量删除；
- `seconds <= 0` 的默认 TTL 语义；
- 类型不匹配返回 null；
- 关闭插件后资源释放。

---

## 5. 适配层通用约束

1. **不复制缓存逻辑**：策略、TTL、负缓存、单飞、降级、版本校验、热 key 协调全部在 `cache4j-core` / `cache4j-local` / `cache4j-central-*`；适配层只转换协议。
2. **不引入第二套连接**：Spring 复用 `RedisConnectionFactory`；Solon 复用其插件；cache4j 不自己建池。
3. **不绑定序列化**：codec 由业务提供；适配层不把框架的 serializer 当成 codec。
4. **不引入调度**：适配层不得为版本校验或热 key 同步创建定时任务/线程池；这些动作由访问事件触发，配置项只是时间间隔。
5. **条件装配**：缺依赖时给出明确启动错误，不静默降级。
6. **可关闭**：每个适配器都有 `enabled` 开关；业务可完全接管。
7. **版本隔离**：适配器依赖的框架版本只在适配模块出现；核心模块的 `pom.xml` 不出现框架坐标。
8. **测试隔离**：框架适配测试使用独立 profile；普通 `mvn test` 不要求 Redis / 容器。

---

## 6. 决策记录

| ID | 决策 | 理由 | 备选 |
|---|---|---|---|
| A1 | `cache4j-spring` 不接 Redis | 职责单一；没有 Redis 也能用本地/内存中央缓存 | 把 Redis 装配塞进 `cache4j-spring` |
| A2 | `cache4j-spring-redis` 只实现 `RedisCommands` | 复用 Spring 连接池/集群/哨兵，不复制 Redis 语义 | 在 Spring 模块里重写 `RedisCentralCache` |
| A3 | 不使用 `RedisTemplate` 的 value serializer | 避免双重序列化；Redis 数据格式由 cache4j 决定 | 直接用 `RedisTemplate` |
| A4 | Spring key 展开 `SimpleKey` | 稳定、可复现；禁止对象哈希进 key | 用 `SimpleKey.toString()` |
| A5 | Solon 通过 `CacheService` 接入 | Solon 注解体系原生依赖该接口；替换默认实现即可 | 自己实现 AOP |
| A6 | tags 优先复用 Solon `CacheTagsServiceImpl` | 语义与 Solon 一致，少写一套 | 完全自实现 |
| A7 | v1 不做 `cache4j-solon-redis` | 先聚焦 Spring 体系；Solon Redis 适配按需增加 | 同时做两套 |
| A8 | 适配层不创建调度任务 | 版本与热 key 同步由访问事件驱动；避免每个缓存实例一个线程 | 适配层注册定时同步 |
