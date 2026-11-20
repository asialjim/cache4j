package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDataSource;
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
import com.asialjim.cache4j.spring.Cache4jSpringOptions;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import com.asialjim.cache4j.spring.redis.RedisKeyFormatter;
import com.asialjim.cache4j.spring.redis.SpringRedisCentralCacheProvider;
import com.asialjim.cache4j.spring.redis.SpringRedisValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 策略仓库落 Redis：枚举声明 → {@link CachedCacheStrategyRepository} 门面 → Redis 里的策略记录，
 * 并且装配层的缓存定义**确实来自仓库**。
 *
 * <p>这里把本机 Redis 同时当作两样东西：</p>
 * <ol>
 *   <li><strong>策略数据源</strong>（{@link RedisStrategyDataSource}）：HASH {@code cache4j:strategy:defs}
 *       存每条策略；</li>
 *   <li><strong>门面自缓存</strong>的中央层：{@code CachedCacheStrategyRepository} 自己是 STATIC
 *       二级缓存，namespace {@code cache4j.strategy}，数据落在 {@code cache4j:{cache4j.strategy}:*}。</li>
 * </ol>
 *
 * <p><strong>跑完不清 Redis</strong>，方便直接观察：</p>
 * <pre>
 * redis-cli hgetall 'cache4j:strategy:defs'              # 策略记录（value 是 key=value 文本）
 * redis-cli --scan --pattern 'cache4j:{cache4j.strategy}:*'   # 门面自缓存
 * </pre>
 *
 * @author Asial Jim
 */
class RedisStrategyRepositoryTest {

    /**
     * 策略记录所在的 HASH。
     */
    private static final String POLICY_HASH = "cache4j:strategy:defs";

    private LettuceConnectionFactory connectionFactory;

    private RedisStrategyDataSource dataSource;

    private CachedCacheStrategyRepository repository;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedisTestEnvironment.redisAvailable(),
                "Redis 不可达（" + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT + "），跳过");
        connectionFactory = RedisTestEnvironment.connectionFactory();
        // 每次开始前清一次，保证可重复；结束不清理，留给人工观察
        cleanup();

        dataSource = new RedisStrategyDataSource(connectionFactory);
        repository = newRepository();
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
    @DisplayName("枚举声明被持久化到 Redis：HASH 里能看到三条策略")
    void declarationsArePersistedToRedis() {
        declareAll();

        Map<String, String> stored = rawHash();
        assertEquals(3, stored.size(), "三条声明应各落一条记录");
        assertTrue(stored.containsKey("decl:static"));
        assertTrue(stored.containsKey("decl:none"));
        assertTrue(stored.containsKey("decl:dynamic"));
        // 内容是可读的 key=value 文本，直接 redis-cli 就能看懂
        assertTrue(stored.get("decl:static").contains("namespace=decl:static"));
        assertTrue(stored.get("decl:static").contains("strategy=STATIC"));

        PolicyLookup lookup = repository.find("decl:static");
        assertTrue(lookup instanceof PolicyLookup.Found, "声明登记后应能从仓库查到");
        assertEquals(1L, ((PolicyLookup.Found) lookup).policy().configVersion(),
                "首次登记的配置版本应为 1");
    }

    @Test
    @DisplayName("装配层的缓存定义确实来自仓库（仓库有运维改过的值，装配就用它）")
    void managerTakesDefinitionsFromRepository() {
        declareAll();

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
    @DisplayName("门面自缓存也在 Redis 上留痕（namespace cache4j.strategy）")
    void selfCacheLeavesTracesInRedis() {
        declareAll();
        // 触发一次缓存写穿 + 回填
        repository.find("decl:none");

        var keys = keys("cache4j:{cache4j.strategy}:*");
        assertFalse(keys.isEmpty(), "门面自己的二级缓存在 Redis 上应有数据，实际 key=" + keys);
    }

    // ------------------------------------------------------------------ 装配辅助

    private void declareAll() {
        for (Decl decl : Decl.values()) {
            repository.declare(decl, "test");
        }
        List<String> namespaces = repository.bootstrap();
        assertEquals(3, namespaces.size(), "bootstrap 返回已解析的 namespace 列表");
    }

    private CachedCacheStrategyRepository newRepository() {
        StrategySelfCacheOptions self = StrategySelfCacheOptions.defaults();
        LocalCache<StoredPolicy> local = new CaffeineLocalCache<>(self.localSpec(), System::nanoTime);
        CentralCache<StoredPolicy> central = new RedisCentralCache<>(self.centralSpec(),
                new SpringRedisValueStore<>(connectionFactory, new RedisKeyFormatter("cache4j", true),
                        new StoredPolicySerializer()));
        return new CachedCacheStrategyRepository(dataSource, self, local, central);
    }

    private Cache4jSpringOptions options() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisSerializer<Object> values = (RedisSerializer) new StringRedisSerializer();
        return Cache4jSpringOptions.builder()
                .centralProvider(new SpringRedisCentralCacheProvider(
                        connectionFactory, new RedisKeyFormatter("cache4j", true), values), true)
                .strategyRepository(repository)
                .declEnums(Decl.class)
                .build();
    }

    // ------------------------------------------------------------------ Redis 辅助

    private Map<String, String> rawHash() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Map<byte[], byte[]> entries = connection.hashCommands().hGetAll(bytes(POLICY_HASH));
            Map<String, String> result = new java.util.LinkedHashMap<>();
            entries.forEach((key, value) -> result.put(text(key), text(value)));
            return result;
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
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(bytes(POLICY_HASH));
            for (String pattern : new String[]{"cache4j:{cache4j.strategy}:*", "cache4j:{decl:*"}) {
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
     * Redis 版的策略数据源：HASH {@code cache4j:strategy:defs}，field = namespace，value = 文本策略。
     *
     * <p>这是测试实现，只够跑通链路：{@link #query} 在内存里分页（生产实现应走 HSCAN 存储侧分页），
     * {@link #update} 的 CAS 也不是原子的（生产应走 Lua 或 WATCH/MULTI）。真实数据源见
     * {@code STRATEGY-REPOSITORY.md}：那属于业务存储，谁接谁实现。</p>
     */
    static final class RedisStrategyDataSource implements CacheStrategyDataSource {

        private final RedisConnectionFactory factory;

        private final TextPolicyCodec codec = new TextPolicyCodec();

        RedisStrategyDataSource(RedisConnectionFactory factory) {
            this.factory = factory;
        }

        @Override
        public Optional<StoredPolicy> load(String namespace) {
            try (RedisConnection connection = factory.getConnection()) {
                byte[] raw = connection.hashCommands().hGet(bytes(POLICY_HASH), bytes(namespace));
                return raw == null ? Optional.empty() : Optional.of(codec.decode(raw));
            }
        }

        @Override
        public Page<StoredPolicy> query(String keyword, int page, int size) {
            List<StoredPolicy> matched = new ArrayList<>();
            try (RedisConnection connection = factory.getConnection()) {
                Map<byte[], byte[]> entries = connection.hashCommands().hGetAll(bytes(POLICY_HASH));
                for (byte[] raw : entries.values()) {
                    StoredPolicy policy = codec.decode(raw);
                    if (keyword == null || keyword.isBlank()
                            || policy.namespace().contains(keyword)
                            || (policy.description() != null && policy.description().contains(keyword))) {
                        matched.add(policy);
                    }
                }
            }
            matched.sort(Comparator.comparing(StoredPolicy::namespace));
            int from = Math.min(page * size, matched.size());
            int to = Math.min(from + size, matched.size());
            return new Page<>(matched.subList(from, to), page, size, matched.size());
        }

        @Override
        public boolean register(StoredPolicy policy) {
            try (RedisConnection connection = factory.getConnection()) {
                Boolean added = connection.hashCommands()
                        .hSetNX(bytes(POLICY_HASH), bytes(policy.namespace()), codec.encode(policy));
                return Boolean.TRUE.equals(added);
            }
        }

        @Override
        public OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
            Optional<StoredPolicy> current = load(policy.namespace());
            if (current.isEmpty() || current.get().configVersion() != expectedConfigVersion) {
                return OptionalLong.empty();
            }
            try (RedisConnection connection = factory.getConnection()) {
                connection.hashCommands()
                        .hSet(bytes(POLICY_HASH), bytes(policy.namespace()), codec.encode(policy));
            }
            return OptionalLong.of(policy.configVersion());
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
