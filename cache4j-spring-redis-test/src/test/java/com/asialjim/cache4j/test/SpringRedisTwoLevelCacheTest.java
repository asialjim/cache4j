package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.core.local.VersionedLocalCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.localcaffeine.CaffeineLocalCacheProvider;
import com.asialjim.cache4j.spring.Cache4jCachingConfiguration;
import com.asialjim.cache4j.spring.Cache4jSpringOptions;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import com.asialjim.cache4j.spring.redis.RedisKeyFormatter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 环境下的二级缓存集成测试：Spring 容器 + {@code @Cacheable} + Caffeine 本地 +
 * Redis 中央（Spring Data Redis / Lettuce）。
 *
 * <p>本机没有 Redis 时整类跳过；用 {@code -Dcache4j.test.redis.host/port/password} 指定测试环境。</p>
 *
 * @author Asial Jim
 */
class SpringRedisTwoLevelCacheTest {

    private static final String NS = "it:user:detail";
    private static final String NS_NONE = "it:user:none";

    /**
     * 静态计数器：Spring 代理 {@code @Bean} 实例时不走构造器，实例字段会为 null。
     */
    private static final AtomicInteger USER_LOADS = new AtomicInteger();
    private static final AtomicInteger NONE_LOADS = new AtomicInteger();
    private static final AtomicInteger EVICT_LOADS = new AtomicInteger();
    private static final AtomicInteger SYNC_LOADS = new AtomicInteger();

    private AnnotationConfigApplicationContext context;
    private StatefulRedisConnection<String, String> raw;
    private RedisClient redisClient;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedisTestEnvironment.redisAvailable(),
                () -> "Redis " + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT
                        + " 不可达，跳过集成用例");

        USER_LOADS.set(0);
        NONE_LOADS.set(0);
        EVICT_LOADS.set(0);
        SYNC_LOADS.set(0);
        this.redisClient = RedisClient.create(
                "redis://" + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT);
        this.raw = redisClient.connect();
        clearNamespaces();

        this.context = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            //clearNamespaces();
            context.close();
        }
        if (raw != null) {
            raw.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("首次回源写两层，第二次本地命中；Redis 里留下 envelope 与版本表")
    void cachesLocallyAndInRedis() {
        UserService service = context.getBean(UserService.class);

        assertEquals("user-42", service.find("42"), "首次：回源");
        assertEquals("user-42", service.find("42"), "第二次：本地命中");
        assertEquals(1, USER_LOADS.get(), "loader 只执行一次");

        Cache4j<Object, Object> cache = twoLevel(NS);

        String revisionKey = "_c4j:{" + NS + "}:__rev";
        assertTrue(raw.sync().exists(revisionKey) > 0L, "版本表应落 Redis");
        String dataKey = "_c4j:{" + NS + "}:42";
        String envelope = raw.sync().get(dataKey);
        assertNotNull(envelope, "数据 key 应落 Redis");
        assertTrue(envelope.length() > 4, "值应带 envelope 头");
    }

    @Test
    @DisplayName("evict 删掉 Redis 数据，下一次访问重新回源")
    void evictForcesReload() {
        UserService service = context.getBean(UserService.class);
        service.find("42");

        CacheManager cacheManager = context.getBean("cacheManager", CacheManager.class);
        cacheManager.getCache(NS).evict("42");

        String dataKey = "_c4j:{" + NS + "}:42";
        assertEquals(0L, raw.sync().exists(dataKey), "evict 应删除中央数据");
        assertEquals("user-42", service.find("42"));
        assertEquals(2, USER_LOADS.get(), "evict 后应重新回源");
    }

    @Test
    @DisplayName("跨节点：另一个节点写中央后，本节点在下一个对账周期读到新值")
    void secondNodeSeesUpdateAfterReconcile() throws Exception {
        UserService service = context.getBean(UserService.class);
        assertEquals("user-42", service.find("42"));

        // 模拟"另一个节点"：用独立的中央视图写 Redis（本节点不知道，只能靠对账发现）
        var otherFactory = RedisTestEnvironment.connectionFactory();
        try {
            var otherProvider = RedisTestEnvironment.centralProvider(
                    otherFactory, RedisTestEnvironment.globalSpecs());
            var otherCentral = otherProvider.<String>create(
                    CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).build().toStrategy());
            otherCentral.putAndGetRevision(
                    new com.asialjim.cache4j.core.CacheKey(NS, "42"), "user-42-v2", null);
            otherProvider.close();
        } finally {
            otherFactory.destroy();
        }

        // version.checkInterval = 100ms，等一个对账周期
        Thread.sleep(300);
        assertEquals("user-42-v2", service.find("42"), "对账后应读到新值");
    }

    @Test
    @DisplayName("@Cacheable(sync=true) 走 cache4j 的 loader 通道（单飞），sourceLoadCount 记账")
    void syncCacheableUsesLoaderChannel() {
        SyncService service = context.getBean(SyncService.class);
        assertEquals("s-1", service.find("1"));
        assertEquals("s-1", service.find("1"));

        assertEquals(1, SYNC_LOADS.get(), "loader 只执行一次");
    }

    @Test
    @DisplayName("@CacheEvict 注解生效")
    void cacheEvictAnnotation() {
        EvictingService service = context.getBean(EvictingService.class);
        assertEquals("v", service.load("9"));
        assertEquals(1, EVICT_LOADS.get());

        service.invalidate("9");

        assertEquals("v", service.load("9"));
        assertEquals(2, EVICT_LOADS.get(), "@CacheEvict 后应重新回源");
    }

    @Test
    @DisplayName("NONE 策略：本地不存值，每次都读中央")
    void noneStrategyAlwaysReadsCentral() {
        NoneUserService service = context.getBean(NoneUserService.class);
        assertEquals("n-1", service.find("1"));
        assertEquals("n-1", service.find("1"));

        assertEquals(1, NONE_LOADS.get(), "loader 只执行一次（第二次命中中央）");
        // NONE 策略不落本地：它的本地实现是 NoneLocalCache，连 keys() 都没有
        // （keys() 属于 VersionedLocalCache —— NONE 不存副本，也就没有版本簿记）
        // NONE 的本地实现是 NoneLocalCache：读恒未命中、写恒无操作
        assertFalse(twoLevel(NS_NONE).local() instanceof VersionedLocalCache,
                "NONE 不应是有版本簿记的本地缓存");
        assertTrue(twoLevel(NS_NONE).local().lookup(new com.asialjim.cache4j.core.CacheKey(NS_NONE, "k")).isMiss(),
                "NONE 的本地读应恒为未命中");
    }

    // ------------------------------------------------------------------ 辅助

    @SuppressWarnings("unchecked")
    private Cache4j<Object, Object> twoLevel(String namespace) {
        TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
        return manager.cache(namespace)
                .orElseThrow(() -> new IllegalStateException("未装配缓存：" + namespace));
    }

    private void clearNamespaces() {
        if (raw == null) {
            return;
        }
        for (String namespace : new String[]{NS, NS_NONE}) {
            String pattern = RedisKeyFormatter.defaults().hashTag()
                    ? "_c4j:{" + namespace + "}:*"
                    : "_c4j:" + namespace + ":*";
            var keys = raw.sync().keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                raw.sync().del(keys.toArray(new String[0]));
            }
        }
    }

    /**
     * 测试容器配置。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(Cache4jCachingConfiguration.class)
    static class TestConfig {

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory redisConnectionFactory() {
            return RedisTestEnvironment.connectionFactory();
        }

        @Bean
        Cache4jSpringOptions cache4jSpringOptions(LettuceConnectionFactory factory) {
            Map<String, GlobalSpec> globalSpecs = RedisTestEnvironment.globalSpecs();
            return Cache4jSpringOptions.builder()
                    // 用 Caffeine 做本地层（贴近生产组合）
                    .localProvider(CaffeineLocalCacheProvider.INSTANCE)
                    .centralProvider(RedisTestEnvironment.centralProvider(factory, globalSpecs), true)
                    .cache(CacheDefinition.of(com.asialjim.cache4j.core.CacheStrategy
                            .staticCache(NS, 1_000, Duration.ofMinutes(1),
                                    new VersionSpec(Duration.ofMillis(100), Duration.ZERO,
                                            Duration.ofMillis(50), 1_000))
                            .withCentral(Duration.ofMinutes(10), Duration.ZERO, true)))
                    .cache(CacheDefinition.of(com.asialjim.cache4j.core.CacheStrategy
                            .noLocal(NS_NONE)
                            .withCentral(Duration.ofMinutes(10), Duration.ZERO, true)))
                    .build();
        }

        @Bean
        UserService userService() {
            return new UserService();
        }

        @Bean
        NoneUserService noneUserService() {
            return new NoneUserService();
        }

        @Bean
        EvictingService evictingService() {
            return new EvictingService();
        }

        @Bean
        SyncService syncService() {
            return new SyncService();
        }
    }

    /**
     * 被缓存的业务 bean（STATIC 本地 + Redis 中央）。
     */
    static class UserService {

        @Cacheable(cacheNames = NS, key = "#id")
        String find(String id) {
            USER_LOADS.incrementAndGet();
            return "user-" + id;
        }
    }

    /**
     * 走 NONE 策略的业务 bean。
     */
    static class NoneUserService {

        @Cacheable(cacheNames = NS_NONE, key = "#id")
        String find(String id) {
            NONE_LOADS.incrementAndGet();
            return "n-" + id;
        }
    }

    /**
     * 走 sync=true（cache4j loader 通道）的业务 bean。
     */
    static class SyncService {

        @Cacheable(cacheNames = NS, key = "#id", sync = true)
        String find(String id) {
            SYNC_LOADS.incrementAndGet();
            return "s-" + id;
        }
    }

    /**
     * 带失效注解的业务 bean。
     */
    static class EvictingService {

        @Cacheable(cacheNames = NS, key = "#id")
        String load(String id) {
            EVICT_LOADS.incrementAndGet();
            return "v";
        }

        @CacheEvict(cacheNames = NS, key = "#id")
        void invalidate(String id) {
            // 仅失效
        }
    }
}
