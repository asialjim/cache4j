package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.core.strategy.CachedCacheStrategyRepository;
import com.asialjim.cache4j.core.strategy.NamespacePolicy;
import com.asialjim.cache4j.core.strategy.Page;
import com.asialjim.cache4j.core.strategy.PolicyResolver;
import com.asialjim.cache4j.core.strategy.StrategyChange;
import com.asialjim.cache4j.core.strategy.PolicyLookup;
import com.asialjim.cache4j.core.strategy.StoredPolicy;
import com.asialjim.cache4j.core.strategy.StrategySelfCacheOptions;
import com.asialjim.cache4j.core.strategy.TextPolicyCodec;
import com.asialjim.cache4j.central.redis.RedisCentralCache;
import com.asialjim.cache4j.localcaffeine.CaffeineLocalCache;
import com.asialjim.cache4j.spring.Cache4jCachingConfiguration;
import com.asialjim.cache4j.spring.Cache4jSpringOptions;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import com.asialjim.cache4j.spring.redis.RedisKeyFormatter;
import com.asialjim.cache4j.spring.redis.SpringRedisCentralCacheProvider;
import com.asialjim.cache4j.spring.redis.SpringRedisValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.cache.annotation.Cacheable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略仓库落本地文件：枚举声明 → {@link CachedCacheStrategyRepository} 门面 →
 * {@code cache4j-spring-redis-test/db/strategy.dat}，并且装配层的缓存定义**确实来自仓库**。
 *
 * <p>两样东西分开看：</p>
 * <ol>
 *   <li><strong>策略数据源</strong>（{@link FileStrategyDataSource}）：本地文件
 *       {@code db/strategy.dat}（模块目录下），一策略一段可读文本；</li>
 *   <li><strong>门面自缓存</strong>的中央层：{@code CachedCacheStrategyRepository} 自己是 STATIC
 *       二级缓存，namespace {@code _c4j:stg}，仍走 Redis。</li>
 * </ol>
 *
 * <p><strong>跑完不删文件、不清 Redis</strong>，方便直接观察：</p>
 * <pre>
 * cat cache4j-spring-redis-test/db/strategy.dat          # 策略记录（key=value 文本）
 * redis-cli --scan --pattern '_c4j:stg:*'                 # 门面自缓存
 * </pre>
 *
 * @author Asial Jim
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileStrategyRepositoryTest {

    /**
     * 策略记录所在的 HASH。
     */


    private LettuceConnectionFactory connectionFactory;

    private FileStrategyDataSource dataSource;

    private CachedCacheStrategyRepository repository;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedisTestEnvironment.redisAvailable(),
                "Redis 不可达（" + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT + "），跳过");
        connectionFactory = RedisTestEnvironment.connectionFactory();
        dataSource = new FileStrategyDataSource(FileStrategyDataSource.DEFAULT_DIRECTORY);
        // 每次开始前清一次，保证可重复；结束不清理，留给人工观察
        cleanup();
        repository = newRepository(connectionFactory, dataSource);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
            repository = null;
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }

    @Test
    @Order(1)
    @DisplayName("启动装配即可：枚举声明被播种进本地文件（测试不手动 declare）")
    void declarationsArePersistedToFile() {
        // 唯一一个动作：启动装配。声明登记、播种、解析全部由装配层完成。
        TwoLevelCacheManager manager = TwoLevelCacheManager.create(options());
        try {
            String content = fileText();
            assertEquals(3, content.split("namespace=").length - 1, "三条声明应各落一段");
            assertTrue(content.contains("namespace=decl:static"));
            assertTrue(content.contains("namespace=decl:none"));
            assertTrue(content.contains("namespace=decl:dynamic"));
            // 直接 cat 就能看懂：策略语义 + 原始 description 都在
            assertTrue(content.contains("strategy=STATIC"));
            assertTrue(content.contains("description=静态本地缓存（仓库落库测试）"));
            assertTrue(content.contains("reason=代码声明播种（bootstrap）"));

            PolicyLookup lookup = repository.find("decl:static");
            assertTrue(lookup instanceof PolicyLookup.Found, "声明登记后应能从仓库查到");
            assertEquals(1L, ((PolicyLookup.Found) lookup).policy().configVersion(),
                    "首次登记的配置版本应为 1");

            // 运维列表页：文件数据源天然全量，过滤在内存做，分页语义与数据源一致
            Page<StoredPolicy> firstPage = dataSource.query("decl:", 0, 2);
            assertEquals(2, firstPage.items().size(), "第一页取 2 条");
            assertEquals(3L, firstPage.total(), "total 应为匹配总数，不是当前页条数");
            Page<StoredPolicy> secondPage = dataSource.query("decl:", 1, 2);
            assertEquals(1, secondPage.items().size(), "第二页只剩 1 条");
            assertEquals(0, dataSource.query("no-such:", 0, 10).total(), "无匹配时 total 为 0");
        } finally {
            manager.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("装配层的缓存定义确实来自仓库（仓库有运维改过的值，装配就用它）")
    void managerTakesDefinitionsFromRepository() {
        // 第一次启动：声明被播种进 Redis
        TwoLevelCacheManager.create(options()).close();

        // 运维改一条：把 decl:static 的本地容量改成 42
        PolicyLookup.Found found = (PolicyLookup.Found) repository.find("decl:static");
        NamespacePolicy origin = found.policy();
        StoredPolicy changed = new StoredPolicy(origin.namespace(), origin.description(), origin.strategy(),
                42L, origin.localTtl(), origin.localNullTtlDivisor(), origin.version(), origin.dynamic(),
                origin.centralTtl(), origin.centralNullTtl(), origin.failOpen(), origin.configVersion(),
                origin.updatedAtMillis(), origin.updatedBy(), origin.reason());
        assertTrue(repository.change(new StrategyChange(
                "req-1", "ops", "把容量调小", System.currentTimeMillis(),
                PolicyResolver.fromStored(null, changed))).applied(), "运维变更应生效");

        TwoLevelCacheManager manager = TwoLevelCacheManager.create(options());
        try {
            assertEquals(Set.of("decl:static", "decl:none", "decl:dynamic"), manager.names());
            assertEquals(42L,
                    manager.cache("decl:static").orElseThrow().local().spec().maxSize(),
                    "装配必须用仓库里的值，而不是代码声明里的内建默认（1000）");
        } finally {
            manager.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("门面自缓存也在 Redis 上留痕（namespace _c4j:stg）")
    void selfCacheLeavesTracesInRedis() {
        TwoLevelCacheManager.create(options()).close();
        // 触发一次缓存写穿 + 回填
        repository.find("decl:none");

        var keys = keys("_c4j:{_stg_}:*");
        assertFalse(keys.isEmpty(), "门面自己的二级缓存在 Redis 上应有数据，实际 key=" + keys);
    }

    @Test
    @Order(99)
    @DisplayName("业务侧 @Cacheable 读写后，decl:* 的数据落在 Redis 里（最后跑，样本留在库里）")
    void businessCachesWriteToRedis() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DeclCachingConfig.class);
            context.refresh();

            DeclService service = context.getBean(DeclService.class);
            assertEquals("static-keep", service.staticValue("keep"));
            assertEquals("none-keep", service.noneValue("keep"));
            assertEquals("dynamic-keep", service.dynamicValue("keep"));

            // 再读一次，确认命中（不会再走到方法体）
            assertEquals("static-keep", service.staticValue("keep"));
        }

        // 三条策略都写到了中央（Redis），key 布局 cache4j:{<namespace>}:*
        assertFalse(keys("_c4j:{decl:static}:*").isEmpty(), "STATIC 应写入中央");
        assertFalse(keys("_c4j:{decl:none}:*").isEmpty(), "NONE 应写入中央");
        assertFalse(keys("_c4j:{decl:dynamic}:*").isEmpty(), "DYNAMIC 冷 key 也应写入中央");
        // 版本表同样在
        assertFalse(keys("_c4j:{decl:static}:__rev").isEmpty(), "版本表应落 Redis");
    }

    // ------------------------------------------------------------------ 装配辅助

    private static CachedCacheStrategyRepository newRepository(
            RedisConnectionFactory connectionFactory, FileStrategyDataSource dataSource) {
        StrategySelfCacheOptions self = StrategySelfCacheOptions.defaults();
        LocalCache<StoredPolicy> local = new CaffeineLocalCache<>(self.localSpec(), System::nanoTime);
        CentralCache<StoredPolicy> central = new RedisCentralCache<>(self.centralSpec(),
                new SpringRedisValueStore<>(connectionFactory, RedisKeyFormatter.defaults(),
                        new StoredPolicySerializer()));
        return new CachedCacheStrategyRepository(dataSource, self, local, central);
    }

    private Cache4jSpringOptions options() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisSerializer<Object> values = (RedisSerializer) new StringRedisSerializer();
        return Cache4jSpringOptions.builder()
                .centralProvider(new SpringRedisCentralCacheProvider(
                        connectionFactory, RedisKeyFormatter.defaults(), values), true)
                .strategyRepository(repository)
                .declEnums(Decl.class)
                .build();
    }

    // ------------------------------------------------------------------ Redis 辅助

    private String fileText() {
        try {
            return java.nio.file.Files.readString(dataSource.file(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取策略文件失败：" + dataSource.file(), e);
        }
    }

    private Set<String> keys(String pattern) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Set<byte[]> found = connection.keyCommands().keys(bytes(pattern));
            Set<String> result = new java.util.TreeSet<>();
            if (found != null) {
                found.forEach(key -> result.add(text(key)));
            }
            return result;
        }
    }

    private void cleanup() {
        try {
            java.nio.file.Files.deleteIfExists(dataSource.file());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("清理策略文件失败：" + dataSource.file(), e);
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            for (String pattern : new String[]{"_c4j:{_stg_}:*", "_c4j:{decl:*"}) {
                Set<byte[]> found = connection.keyCommands().keys(bytes(pattern));
                if (found != null && !found.isEmpty()) {
                    connection.keyCommands().del(found.toArray(new byte[0][]));
                }
            }
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 声明

    /**
     * 缓存策略声明——业务侧要写的就只有这一段。
     */
    @CacheNamespace
    public enum Decl implements CacheStrategyDecl {

        /**
         * 静态本地缓存。
         */
        STATIC("decl:static", LocalStrategy.STATIC, "静态本地缓存（仓库落库测试）"),

        /**
         * 只走中央。
         */
        NONE("decl:none", LocalStrategy.NONE, "只走中央缓存（仓库落库测试）"),

        /**
         * 动态本地缓存。
         */
        DYNAMIC("decl:dynamic", LocalStrategy.DYNAMIC, "动态本地缓存（仓库落库测试）");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        Decl(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    // ------------------------------------------------------------------ 数据源

    /**
     * 业务侧最小装配：{@code @Cacheable} 需要 Spring 容器，所以这里单独起一个。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(Cache4jCachingConfiguration.class)
    static class DeclCachingConfig {

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory redisConnectionFactory() {
            return RedisTestEnvironment.connectionFactory();
        }

        @Bean
        CentralCacheProvider centralCacheProvider(LettuceConnectionFactory factory) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            RedisSerializer<Object> values = (RedisSerializer) new StringRedisSerializer();
            return new SpringRedisCentralCacheProvider(
                    factory, RedisKeyFormatter.defaults(), values);
        }

        @Bean(destroyMethod = "close")
        CachedCacheStrategyRepository strategyRepository(LettuceConnectionFactory factory) {
            return newRepository(factory, new FileStrategyDataSource(FileStrategyDataSource.DEFAULT_DIRECTORY));
        }

        @Bean
        Cache4jSpringOptions cache4jOptions(CentralCacheProvider provider,
                                            CachedCacheStrategyRepository repository) {
            return Cache4jSpringOptions.builder()
                    .centralProvider(provider, false)
                    .strategyRepository(repository)
                    .declEnums(Decl.class)
                    .build();
        }

        @Bean
        DeclService declService() {
            return new DeclService();
        }
    }

    /**
     * 被缓存的业务方法：三条声明各一个，方法体就是"数据源"。
     */
    static class DeclService {

        @Cacheable(cacheNames = "decl:static", key = "#id")
        public String staticValue(String id) {
            return "static-" + id;
        }

        @Cacheable(cacheNames = "decl:none", key = "#id")
        public String noneValue(String id) {
            return "none-" + id;
        }

        @Cacheable(cacheNames = "decl:dynamic", key = "#id")
        public String dynamicValue(String id) {
            return "dynamic-" + id;
        }
    }

    /**
     * {@link StoredPolicy} 与文本策略之间的序列化（复用 core 的 {@link TextPolicyCodec}）。
     */
    static final class StoredPolicySerializer implements RedisSerializer<StoredPolicy> {

        private final TextPolicyCodec codec = new TextPolicyCodec();

        @Override
        public byte[] serialize(StoredPolicy value) {
            return value == null ? null : codec.encode(value);
        }

        @Override
        public StoredPolicy deserialize(byte[] bytes) {
            return bytes == null || bytes.length == 0 ? null : codec.decode(bytes);
        }
    }
}
