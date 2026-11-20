# 框架适配设计（`cache4j-spring` / `cache4j-spring-redis` / `cache4j-solon`）

> 本文是 [总体设计](DESIGN.md) 的展开。核心库与框架无关；本文件只讨论"如何把核心接进 Spring / Solon"。
>
> **硬边界**：
>
> - `cache4j-core` / `local` / `central` / `central-redis` 不出现 Spring、Solon、Jedis、Lettuce、Redisson 依赖；
> - `cache4j-central` 定义中央缓存 SPI，不认识 Redis；
> - `cache4j-central-redis` 定义 **Redis 语义层 + 泛型下行 SPI**（`RedisValueStore<V>`、版本/负缓存/热 key 语义），**没有客户端、也不碰字节**；
> - `cache4j-spring-redis` 是**真正做 I/O 的适配层**：把 `RedisConnectionFactory` 实现成 `RedisValueStore<V>`，并自己负责 key 布局、envelope、Lua 与 serializer；底层客户端由用户选择；
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
| `cache4j-spring` | Spring Cache `CacheManager` / `Cache` 适配、本地缓存与二级缓存的 Bean 装配（Java Config / XML）、错误处理 | Redis 连接、Redis 命令、**属性绑定**（Boot 才有的能力） |
| `cache4j-spring-redis` | 用 `RedisConnectionFactory` 实现 `RedisValueStore<V>`：连接 + key 布局 + envelope + Lua + serializer；提供 `SpringRedisCentralCacheProvider` 开箱装配 | Spring Cache 适配、版本/热 key 语义、锁定底层客户端 |
| `cache4j-solon` | **（计划中，暂不实现）** Solon `CacheService` / `CacheTagsService` 实现、插件生命周期 | 缓存策略、Redis 连接 |
| `cache4j-starter` | **（计划中）** 依赖聚合 + 默认装配 | 编排逻辑（在 `cache4j-core`）、框架绑定 |

> **不做 `cache4j-spring-boot-starter` / 任何自动配置模块。** `spring.factories`、`@AutoConfiguration`、
> `@ConfigurationProperties` 都是 Boot 机制；本库按约定只用 Spring Framework —— 装配靠 Java Config 或 XML。

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

### 2.1 装配方式（纯 Spring Framework）

**没有自动配置机制。** 纯 Spring Framework 不具备"扫描 classpath 自动注册 Bean"的标准能力
（`spring.factories`、`@AutoConfiguration` 都是 Spring Boot 的东西；本项目按约定**不依赖 Boot**）。
所以装配只有两条等价的路：**Java Config** 与 **XML**。

| 想要什么 | 怎么做 |
|---|---|
| 只要二级缓存对象 | `@Import(Cache4jSpringConfiguration.class)`，注入 `TwoLevelCacheManager` |
| 要用 `@Cacheable` | `@Import(Cache4jCachingConfiguration.class)`（已带上前者，注册名为 `cacheManager` 的 `CacheManager` + `@EnableCaching`） |
| 自己已有 `CacheManager` | **不要 import** 上面两个类，自行 `new SpringCacheManagerAdapter(...)` 装配（纯 Spring 没有 `@ConditionalOnMissingBean`，重复定义直接冲突） |
| 自动扫描（含子模块） | `@Import(Cache4jAutoScanConfiguration.class)`：扫 `com.asialjim.cache4j` 包，classpath 上有 `cache4j-spring-redis` 就自动注册它的配置类（见下） |
| XML 装配 | `<import resource="classpath:META-INF/cache4j-context.xml"/>`（**库产物**，随 jar 发布）；文件里只有组件扫描，其余全走注解 |

#### 用组件扫描代替 `@ConditionalOnClass`

纯 Spring 没有 `@ConditionalOnClass`，但也不需要：`@ComponentScan` **天然只看得见 classpath 上真实存在的类**。

```java
@Configuration
@EnableCaching
@Import(Cache4jAutoScanConfiguration.class)
public class Cache4jConfig {

    @Bean
    public Cache4jSpringOptions cache4jOptions(CentralCacheProvider provider) {
        return Cache4jSpringOptions.builder()
                .centralProvider(provider, false)
                .declPackages(List.of("com.acme.cache"))
                .build();
    }
}
```

- 引了 `cache4j-spring-redis` → 扫到 `Cache4jRedisSpringConfiguration` → `cache4jCentralProvider` 自动就位；
- 没引 → 扫不到 → 业务自己提供 `CentralCacheProvider`，不会莫名其妙去连 Redis；
- 业务侧只需要给 `Cache4jSpringOptions`（唯一必须人工决定的东西：策略声明包在哪）。

> `Cache4jAutoScanConfiguration` 与 `Cache4jCachingConfiguration` **选一个** import：都 import 会重复注册
> `cacheManager`。前者一把梭，后者精确控制。

- `CentralCacheProvider` 由 `cache4j-spring-redis`（或用户自定义 Bean）提供；`cache4j-spring` 只按类型注入，不依赖 Redis 模块；
- 容器里没有 `CentralCacheProvider` 时**启动失败**并给出明确提示，不静默降级成内存缓存；
- `InMemoryCentralCache` 仅用于开发/测试；
- 装配在启动期一次性定下来，运行期不可变（`Cache4jSpringOptions` 不可变 + builder）。

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

### 2.5 值编解码（序列化归适配层）

**谁的 I/O 谁关心 codec**：`cache4j-core`、`cache4j-central`、`cache4j-central-redis` 都不知道序列化；
只有真正读写 Redis 的模块才决定"值怎么变成字节"。Spring 适配层用 **`RedisSerializer<V>`**：

```java
public interface CacheSerializerResolver {
    /** 按缓存名解析 serializer；返回 null 表示该缓存未配置，启动阶段应失败 */
    RedisSerializer<?> resolve(String cacheName);
}
```

- 默认实现：
  1. `cache4j.caches.<name>.serializer` 指定的 Bean 名；
  2. 全局唯一的 `RedisSerializer` Bean；
  3. 都没有 ⇒ **启动阶段失败**并提示配置（不静默回退 JDK 序列化）；
- 推荐为每类实体提供一个 JSON serializer Bean（Jackson 适配由 `util4j` 或业务提供）；
- **不使用 `RedisTemplate` 的 value serializer 做二次包装**：`RedisConnection` 的字节命令只调用一次，
  encode/decode 各自只发生一次（envelope 直接写在这份字节里）；
- 兼容性：不同适配器 / 不同 serializer 写出的数据**互不兼容**，同一 key 空间必须统一；更换时 bump
  `globalPrefix` 或清空缓存，否则会抛 `CODEC_DECODE_FAILED`（显式失败，不静默当 miss）。

### 2.6 配置示例

配置对象是 `Cache4jSpringOptions`（builder，装配期一次性定死），**没有 YAML 形态** —— YAML 绑定是
Boot 的 `@ConfigurationProperties` 机制，本库不依赖 Boot。两种写法：

**Java Config**

```java
@Configuration
@Import(Cache4jCachingConfiguration.class)
public class Cache4jConfig {

    @Bean
    public RedisSerializer<Object> cache4jSerializer(ObjectMapper objectMapper) {
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    @Bean
    public Cache4jSpringOptions cache4jOptions(RedisConnectionFactory factory,
                                               RedisSerializer<Object> serializer) {
        return Cache4jSpringOptions.builder()
                .centralProvider(new SpringRedisCentralCacheProvider(
                        factory, RedisKeyFormatter.defaults(), serializer), false)
                .declPackages(List.of("com.acme"))   // 扫描 @CacheNamespace
                .statsEnabled(true)
                .undeclaredEnabled(true)
                .build();
    }
}
```

**XML**

见 §3.3 的 `cache4j-context.xml` 模板（同一套 Bean，用 `Cache4jOptionsFactoryBean` 桥接配置对象）。

> **策略参数不在这里配。** TTL / 容量 / 热 key 阈值属于"策略"，由声明枚举（`@CacheNamespace`）
> 或策略仓库（`CacheStrategyDataSource`）给出；见 `STRATEGY.md` 与 `STRATEGY-REPOSITORY.md`。
> 把它们再做成散装属性会和声明打架，所以刻意不放。

### 2.7 不依赖 Spring Boot

`cache4j-spring` 只用 **Spring Framework**（`spring-context` 提供 `org.springframework.cache` 抽象），
**不依赖 spring-boot / spring-boot-autoconfigure**，也不需要任何自动配置注册文件：

| 想要什么 | 怎么做 |
|---|---|
| 只要二级缓存对象 | `@Import(Cache4jSpringConfiguration.class)`，注入 `TwoLevelCacheManager` |
| 要用 `@Cacheable` | `@Import(Cache4jCachingConfiguration.class)`（它已带上前者，并注册名为 `cacheManager` 的 Bean + `@EnableCaching`） |
| 自己已有 `CacheManager` | **不要 import 上面两个类**，直接 `new SpringCacheManagerAdapter(...)` 自行装配（纯 Spring 没有 `@ConditionalOnMissingBean`，重复定义会冲突） |

配置对象是纯 Java 的 `Cache4jSpringOptions`（builder），XML 装配用 `Cache4jOptionsFactoryBean` 桥接；
**不做 `@ConfigurationProperties` 绑定** —— 属性绑定是 Boot 的职责（本库不依赖 Boot）。

> `@Cacheable(key = "#id")` 这类按参数名取值的 SpEL 需要编译时 `-parameters`；本工程父 pom 已开启。

### 2.8 生命周期与禁用

- `Cache4jSpringOptions.builder().enabled(false)` 关闭整个管理器（装配层仍可存在但不装配缓存）；
- 业务已定义 `CacheManager` 时不要 import 本模块的两个配置类（纯 Spring 无 `@ConditionalOnMissingBean`）；
- `TwoLevelCacheManager` 的 `close()` 由 Spring 容器销毁回调触发；先关缓存，再关 provider；
- 不注册任何 `TaskScheduler` / `@Scheduled` 用于版本或热 key 同步；
- 不在启动阶段主动 `ping` Redis；健康检查单独提供。

### 2.9 测试

- `AnnotationConfigApplicationContext`（纯 Spring，无 Boot）：装配、缓存名映射、声明扫描；
- `ClassPathXmlApplicationContext`：XML 装配（§3.3 那份模板）；
- `@Cacheable` / `@CachePut` / `@CacheEvict` / `sync=true` 的行为；
- `CachingConfigurer` 唯一性；
- key 生成器与 `SimpleKey` 展开；
- 错误处理：`failOpen=true/false`；
- 版本检查与热 key 同步按访问触发，容器里没有额外调度 Bean；
- 禁用开关。

---

## 3. `cache4j-spring-redis`

### 3.1 职责

只做一件事：把 Spring Data Redis 的连接适配成 `RedisValueStore<V>`，让 `cache4j-central-redis` 的语义层可以工作。key 布局、envelope、Lua 与 **serializer** 也都在本模块。

```text
用户选择的底层客户端（Jedis / Lettuce / 其他）
        ↓
Spring Data Redis: RedisConnectionFactory
        ↓
SpringRedisValueStore implements RedisValueStore<V>   (cache4j-spring-redis)
        ↓
RedisCentralCache                                     (cache4j-central-redis)
        ↓
TwoLevelCache                                 (cache4j-core)
```

它**不**实现版本语义、负缓存策略、TTL 抖动、热 key 判定与命中统计；这些在 `cache4j-central-redis` / `cache4j-core`。它**要**实现 key 布局、envelope、Lua、序列化——因为真正做 I/O 的是它。它也**不**决定底层客户端：Jedis、Lettuce 或其他实现由用户在 Spring Data Redis 侧选择。

### 3.2 `SpringRedisValueStore`

```java
public final class SpringRedisValueStore<V> implements RedisValueStore<V> {

    private final RedisConnectionFactory factory;
    private final RedisKeyFormatter keys;          // 本模块自己的 key 布局
    private final RedisSerializer<V> serializer;   // 本模块自己的序列化方案

    @Override
    public VersionedLookup<V> read(CacheKey key) {
        try (RedisConnection c = factory.getConnection()) {
            // 一次往返：HGET __rev field + GET data（同一 hash tag 下用一个 Lua 合成）
            List<?> raw = (List<?>) c.scriptingCommands().eval(
                    RedisScripts.READ_WITH_REVISION, ReturnType.MULTI,
                    2, keys.revision(key.namespace()), keys.format(key),
                    key.encoded().getBytes(UTF_8));

            long revision = decodeRevision((byte[]) raw.get(0));     // 缺失 → 0
            byte[] data = (byte[]) raw.get(1);
            if (data == null) {
                return new VersionedLookup<>(revision, Lookup.miss());
            }
            Envelope env = Envelope.decode(data);                    // magic / version / flags
            if (env.negative()) {
                return new VersionedLookup<>(revision, Lookup.negative());
            }
            return new VersionedLookup<>(revision, Lookup.hit(serializer.deserialize(env.payload())));
        } catch (RuntimeException e) {
            throw translate(e);                                      // RedisAccessException
        }
    }

    @Override
    public long write(CacheKey key, V value, Duration ttl) {
        byte[] payload = serializer.serialize(value);                // 失败 → CODEC_ENCODE_FAILED
        byte[] raw = Envelope.value(payload);
        try (RedisConnection c = factory.getConnection()) {
            // 一个 Lua：SET data PX ttl + HSET __rev field "<rev>|<expireAt>"
            return (Long) c.scriptingCommands().eval(
                    RedisScripts.WRITE_WITH_REVISION, ReturnType.INTEGER,
                    2, keys.revision(key.namespace()), keys.format(key),
                    /* ARGV */ encodeArgs(key, raw, ttl, nowMillis()));
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    // writeNegative / writeIfAbsent / delete / clear / revisions / revision / reportHotKey* 同理
}
```

要点：

- **key 布局、envelope、Lua、serializer 全在本模块**：`RedisCentralCache` 只看见 `V` 与 `CacheKey`；
- `RedisConnection` 不是线程安全的：每次操作取用、`close()` 归还；连接池/原生连接由 Spring 管理；
- `clear` 用 `keyCommands().scan(ScanOptions...)`，cluster 需逐节点（v1 标记 best-effort）；禁止 `KEYS`；
- 底层 `RedisConnectionFailureException` / `RedisSystemException` 翻译成 `RedisAccessException`；
- **序列化失败直接抛 `CacheException(CODEC_ENCODE_FAILED / CODEC_DECODE_FAILED)`**，不静默当 miss；
- 换 Redisson / Solon 插件：实现同一接口，key/envelope/Lua/serializer 换成自己的，语义层不动。

### 3.3 装配（纯 Spring Framework，无 Boot）

`cache4j-spring-redis` 提供 **`SpringRedisCentralCacheProvider`**：把 `SpringRedisValueStore`
（做 I/O）与 `RedisCentralCache`（版本 / 负缓存 / TTL 抖动 / 对账语义）拼好。不给这个类的话，
每个使用者都要手抄同样的十行，抄错（时钟、key formatter、序列化）产生的都是隐蔽 bug。

**策略数据源不在其中**：那是业务存储（Redis/DB/配置中心），谁接谁实现 `CacheStrategyDataSource`。

#### XML 模板（库产物，随 jar 发布）

完整内容在 **`cache4j-spring/src/main/resources/META-INF/cache4j-context.xml`**，全部内容就是：

```xml
<context:annotation-config/>

<bean id="innerAsialJimCache4jSpringConfiguration"
      class="com.asialjim.cache4j.spring.Cache4jAutoScanConfiguration"/>
```

业务侧一行接入：

```xml
<import resource="classpath:META-INF/cache4j-context.xml"/>
```

只有这两行是刻意的：**XML 与注解两条路的装配内容必须一致**，靠"XML 里声明一批 bean + 注解里再声明一批"迟早会腐烂。
所以 XML 只负责把扫描配置推进容器，剩下的组件（provider / options / 管理器 / Spring Cache 桥接 /
序列化器）全部由 `@Configuration` 提供，被 `@ComponentScan` 扫到即生效。

> **`<context:annotation-config/>` 不能省。** 纯 XML 容器默认不注册注解处理器，没有它那个
> `@Configuration` 类不会被 `ConfigurationClassPostProcessor` 解析——bean 存在，但
> `@ComponentScan` / `@Autowired` / `@Value` 全是死代码，扫不出任何东西。

业务侧仍要自己提供的两件事：

| 项 | 为什么 |
|---|---|
| `RedisConnectionFactory` | 客户端由你选（Lettuce / Jedis），`cache4j-spring-redis` 不锁定客户端 |
| 值序列化器（可选） | 不提供则用库的默认（JSON，缺 jackson 时退回 JDK）；定义 `cache4jSerializer` 覆盖 |

#### 声明枚举的两种发现方式

**① 交给 Spring 的组件扫描（推荐，不用配包名）**

`@CacheNamespace` 是纯标记（`cache4j-core` 不依赖 Spring，加不了 `@Component`），所以用
include-filter 把它拉进你的 `component-scan` 即可：

```xml
<context:component-scan base-package="com.acme">
    <context:include-filter type="annotation"
            expression="com.asialjim.cache4j.core.strategy.CacheNamespace"/>
</context:component-scan>
```

注解方式：

```java
@Configuration
@ComponentScan(basePackages = "com.acme",
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION, classes = CacheNamespace.class))
public class AcmeCache4jConfig { }
```

扫进来的枚举会被 `Cache4jDeclBeanFactoryPostProcessor` 接管：

> Spring 默认拿枚举的构造器做自动装配（`(String, LocalStrategy, String)`），找不到 `String` Bean
> 直接启动失败——枚举本来就该取常量，不该被 new。

`Cache4jDeclBeanFactoryPostProcessor` 的做法是**换掉 Bean 定义的类型**，而不是让枚举变得能被 new：
Bean 名保留，类型改成 `CacheNamespaceDeclarations`（库的注册载体），实例化用 `getEnumConstants()`。

于是：

| 事实 | 结果 |
|---|---|
| 容器里没有业务枚举 Bean | 应用不会误注入它 |
| 载体持有该枚举的**全部**常量 | `STATIC` / `NONE` / `DYNAMIC` 一次性到齐 |
| 只碰 `CacheStrategyDecl` 的**枚举** | 其他枚举/类的 Bean 定义一概不动 |

`CacheNamespaceDeclarations` 只是"发现结果的载体"，应用不需要、也不应该从容器取它；
真正消费它的是 `Cache4jDefaultOptionsConfiguration`。业务自己用 `@Bean` 提供的非枚举
`CacheStrategyDecl` 单例也照样认（走 `ObjectProvider<CacheStrategyDecl>`）。

**② 配包名让库自己扫**

`cache4j.decl.packages=com.acme.cache`（逗号分隔）。适合没有 `component-scan` 的纯 XML 项目。

两种可以混用：重复的 namespace 会按 namespace 去重，不会报"来源冲突"。

#### Java Config 等价写法

```java
@Configuration
@Import(Cache4jCachingConfiguration.class)
public class Cache4jConfig {

    @Bean
    public Cache4jSpringOptions cache4jOptions(RedisConnectionFactory factory,
                                               RedisSerializer<Object> serializer) {
        return Cache4jSpringOptions.builder()
                .centralProvider(new SpringRedisCentralCacheProvider(
                        factory, RedisKeyFormatter.defaults(), serializer), false)
                .declPackages(List.of("com.acme.cache"))
                .build();
    }
}
```

#### 约定的边界

- 用 `spring.data.redis.*` 或自己声明 `RedisConnectionFactory`（host/port/SSL/sentinel/cluster/pool 都归它）；
- `cache4j-spring-redis` 的 `pom.xml` 只依赖 `spring-data-redis` API，**不依赖** `spring-boot-starter-data-redis`，也不锁定 Jedis / Lettuce；
- 两种写法都**不覆盖**用户已有的 `RedisConnectionFactory` / `CacheManager`（XML 靠"别重复声明"，Java Config 靠"别 import 两个配置类"——纯 Spring 没有 `@ConditionalOnMissingBean`）；
- 类路径没有 Spring Data Redis 时直接编译失败（`CentralCacheProvider` 的构造签名就在那里），不存在"静默降级成内存缓存"。

### 3.4 序列化边界

| 层 | 负责 | 不负责 |
|---|---|---|
| 业务 | 实体 + （可选）自己的 `RedisSerializer<V>` | — |
| `cache4j-core` / `cache4j-central-redis` | 编排、版本、负缓存、热 key **语义** | 序列化、key 布局、envelope、Lua |
| `cache4j-spring-redis` | 连接 + key 布局 + envelope + Lua + **serializer** | 缓存语义 |
| Spring Data Redis | 连接池/集群/哨兵；客户端（Jedis / Lettuce / 其他）由用户选择 | value 序列化（不做二次包装） |

### 3.5 集群与清理

- `MGET`：默认 hash tag 保证同一缓存同 slot；
- `SCAN`：standalone / sentinel 直接可用；cluster 需要逐 master 节点，v1 先按 best-effort 实现并在文档标注；
- `clear` 可能较慢；已通过 per-key 版本 + 批量对账做逻辑失效（`DEL __rev` 后下一轮对账会发现“中央缺字段”并清掉副本），物理删除可异步/分批；
- `RedisConnection` 的 cluster 连接需要处理 `MOVED`/`ASK`；Spring Data Redis 已处理重定向，适配器不重复实现；
- `MGET rev key` 依赖同一个 hash tag；关闭 hash tag 时改为同一次 pipeline 发送 `GET rev` + `GET key`。

### 3.6 测试

- 真机 Redis（`Assumptions` 门控，不可达自动跳过；可用 `-Dcache4j.test.redis.host/port/password` 覆盖）；
- `SpringRedisCentralCacheProvider` 装配 + `XmlAssemblyTest` 走真实 XML 容器；
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
    // Solon 侧同样把编解码放在这一层（CacheService 存的是 Object，由本模块决定序列化）

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

> v1 的 `cache4j-solon` 不提供 Redis 客户端适配。若 Solon 应用需要 Redis，按同样模式新增 `cache4j-solon-redis`：用 Solon 的 Redis 插件实现 `RedisValueStore<V>`（含自己的 key/envelope/Lua/serializer），再注入 `RedisCentralCacheProvider`。它与 `cache4j-spring-redis` 对称，不把 Solon 依赖带进 `cache4j-central-redis`。

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
| A2 | `cache4j-spring-redis` 实现 `RedisValueStore<V>`（连接 + key 布局 + envelope + Lua + serializer） | 复用 Spring 连接池/集群/哨兵，不复制版本与热 key 语义；序列化留在真正做 I/O 的地方 | 在 Spring 模块里重写 `RedisCentralCache` |
| A3 | 不使用 `RedisTemplate` 的 value serializer | 避免双重序列化；Redis 数据格式由 cache4j 决定 | 直接用 `RedisTemplate` |
| A4 | Spring key 展开 `SimpleKey` | 稳定、可复现；禁止对象哈希进 key | 用 `SimpleKey.toString()` |
| A5 | Solon 通过 `CacheService` 接入 | Solon 注解体系原生依赖该接口；替换默认实现即可 | 自己实现 AOP |
| A6 | tags 优先复用 Solon `CacheTagsServiceImpl` | 语义与 Solon 一致，少写一套 | 完全自实现 |
| A7 | v1 不做 `cache4j-solon-redis` | 先聚焦 Spring 体系；Solon Redis 适配按需增加 | 同时做两套 |
| A8 | 适配层不创建调度任务 | 版本与热 key 同步由访问事件驱动；避免每个缓存实例一个线程 | 适配层注册定时同步 |
