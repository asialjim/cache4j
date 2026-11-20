package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DYNAMIC} 策略在真实 Redis 下的热升 / 热降 / 冷却集成测试。
 *
 * <p>日志依次应出现（DEBUG 级，本模块已把 {@code com.asialjim.cache4j} 开到 debug）：</p>
 * <pre>{@code
 * [it:hot:dynamic]-热key-升热，来源=本地阈值，key=it:hot:dynamic#xxxx，估计频次=3，热点集合大小=1
 * [it:hot:dynamic]-热key-降热，来源=中央 COOLING，key=it:hot:dynamic#xxxx，进入冷却=PT2S，热点集合大小=0
 * [it:hot:dynamic]-热key-冷却期内拒绝升热，key=it:hot:dynamic#xxxx，估计频次=6，剩余冷却=xxxxms
 * }</pre>
 *
 * <p>时间参数被刻意压小（窗口 1s、同步 200ms、冷却 2s），让整个用例只睡几秒。</p>
 *
 * @author Asial Jim
 */
class DynamicHotKeyRedisTest {

    private static final String NS = "it:hot:dynamic";

    private AnnotationConfigApplicationContext context;
    private TwoLevelCache<Object, Object> cache;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedisTestEnvironment.redisAvailable(),
                () -> "Redis " + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT
                        + " 不可达，跳过集成用例");
        context = new AnnotationConfigApplicationContext(DynamicHotKeyTestConfig.class);
        cache = context.getBean(TwoLevelCacheManager.class)
                .cache(NS)
                .orElseThrow(() -> new IllegalStateException("未装配缓存：" + NS));
        cache.clear();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            //cache.clear();
            context.close();
        }
    }

    @Test
    @DisplayName("热升：本地频次达到阈值后进入热点集合，本地开始命中")
    void promotesWhenLocalThresholdReached() {
        cache.put("k1", "v1");

        // localHotThreshold = 3：前两次访问不落本地，第 3 次升热
        cache.lookup("k1");
        cache.lookup("k1");
        assertEquals(0L, hotKeyCount(), "未达阈值不应升热");

        cache.lookup("k1");
        assertEquals(1L, hotKeyCount(), "达到阈值应立即本地升热");
        assertEquals(1L, cache.stats().localPromotionCount(), "应记一次本地升热");

        // 升热后本地命中（不再打 Redis）
        long centralBefore = cache.stats().centralHitCount();
        cache.lookup("k1");
        assertTrue(cache.stats().localHitCount() >= 1L, "升热后应命中本地");
        assertEquals(centralBefore, cache.stats().centralHitCount(), "本地命中不应再读中央");

        // 热 key 的本地副本被清掉后：仍是热点，但这一次要硬读中央并回填
        cache.local().evict(new com.asialjim.cache4j.core.CacheKey(NS, "k1"));
        assertEquals("v1", cache.lookup("k1").optional().orElseThrow());
        assertEquals(centralBefore + 1L, cache.stats().centralHitCount(),
                "热 key 本地 miss 时应硬读中央");
        assertEquals(1L, hotKeyCount(), "本次读不应影响热点身份");
        assertTrue(cache.local().containsKey(new com.asialjim.cache4j.core.CacheKey(NS, "k1")),
                "热 key 读中央后应回填本地");
    }

    @Test
    @DisplayName("热升 → 热降 → 冷却：完整生命周期，且冷却期内拒绝重新升热")
    void promoteDemoteAndCooldown() throws Exception {
        cache.put("k2", "v2");

        // ---------- 1) 热升（本地阈值 3）----------
        for (int i = 0; i < 3; i++) {
            cache.lookup("k2");
        }
        awaitTrue(() -> hotKeyCount() == 1L, "应升热");
        assertEquals(1L, cache.stats().localPromotionCount());

        // 让中央也看到足够流量（HOT），避免降热立刻发生
        for (int i = 0; i < 5; i++) {
            cache.lookup("k2");
        }
        Thread.sleep(250);            // 过 syncInterval(200ms)
        cache.lookup("k2");           // 触发一次上报：本轮 delta 累计上报给中央
        assertEquals(1L, hotKeyCount(), "上报后仍应保持热点");
        assertTrue(cache.stats().hotSyncCount() >= 1L, "应完成一次热 key 上报");

        // ---------- 2) 热降（中央判定 COOLING）----------
        // 静默两个窗口（2 × 1s），让集群窗口计数清零；随后一次访问上报 delta=1，
        // 低于 coolThreshold(4) → 触发 demoteWindows(1) → COOLING
        Thread.sleep(2_200);
        cache.lookup("k2");
        awaitTrue(() -> hotKeyCount() == 0L, "中央判定 COOLING 后应降热");
        assertTrue(cache.stats().globalDemotionCount() >= 1L, "应记一次中央降热");
        assertEquals(cache.stats().promotionCount(), cache.stats().demotionCount(),
                "升降热次数应配平");

        // ---------- 3) 冷却（冷却期内拒绝重新升热）----------
        CacheStats afterDemote = cache.stats();
        assertEquals(0L, afterDemote.hotKeyCount(), "降热后热点集合应为空");

        // 冷却期（2s）内继续高频访问：频次早已超过阈值，但不得重新升热
        for (int i = 0; i < 8; i++) {
            cache.lookup("k2");
        }
        assertEquals(0L, hotKeyCount(),
                "冷却期内即使频次达标也不能重新升热（日志应出现『冷却期内拒绝升热』）");
        assertEquals(1L, cache.stats().localPromotionCount(), "冷却期内不应新增本地升热");

        // ---------- 4) 冷却结束后可以再次升热 ----------
        Thread.sleep(2_200);
        for (int i = 0; i < 4; i++) {
            cache.lookup("k2");
        }
        awaitTrue(() -> hotKeyCount() == 1L, "冷却结束后应能再次升热");
        assertTrue(cache.stats().localPromotionCount() >= 2L, "应记第二次本地升热");
    }

    @Test
    @DisplayName("冷 key 的写入不落本地（写不计频次）")
    void coldKeyPutIsDiscarded() {
        cache.put("k3", "v3");
        assertEquals(1L, cache.stats().dynamicPutDiscarded(), "冷 key 写入应被丢弃并计数");
        assertEquals(0L, hotKeyCount());
        assertFalse(cache.local().containsKey(new com.asialjim.cache4j.core.CacheKey(
                NS, "s2:k3")), "冷 key 不应在本地");
    }

    // ------------------------------------------------------------------ 辅助

    private long hotKeyCount() {
        return cache.stats().hotKeyCount();
    }

    private void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /**
     * 测试容器配置：DYNAMIC + GLOBAL，时间参数压小。
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(
            com.asialjim.cache4j.spring.Cache4jCachingConfiguration.class)
    static class DynamicHotKeyTestConfig {

        @org.springframework.context.annotation.Bean(destroyMethod = "destroy")
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnectionFactory() {
            return RedisTestEnvironment.connectionFactory();
        }

        @org.springframework.context.annotation.Bean
        com.asialjim.cache4j.spring.Cache4jSpringOptions cache4jSpringOptions(
                org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory) {

            java.util.Map<String, com.asialjim.cache4j.core.local.LocalCacheSpec.GlobalSpec> globalSpecs =
                    RedisTestEnvironment.globalSpecs();
            globalSpecs.put(NS, RedisTestEnvironment.hotGlobalSpec());

            return com.asialjim.cache4j.spring.Cache4jSpringOptions.builder()
                    .localProvider(com.asialjim.cache4j.localcaffeine.CaffeineLocalCacheProvider.INSTANCE)
                    .centralProvider(RedisTestEnvironment.centralProvider(factory, globalSpecs), true)
                    .cache(com.asialjim.cache4j.core.strategy.CacheDefinition.of(
                            com.asialjim.cache4j.core.local.LocalCacheSpec.dynamicCache(
                                    NS, 1_000, Duration.ofMinutes(1), RedisTestEnvironment.hotDynamicSpec()),
                            com.asialjim.cache4j.core.central.CentralCacheSpec.builder(NS)
                                    .ttl(Duration.ofMinutes(10))
                                    .build()))
                    .build();
        }
    }
}
