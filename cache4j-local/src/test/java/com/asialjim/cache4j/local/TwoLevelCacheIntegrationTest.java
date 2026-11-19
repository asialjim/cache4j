package com.asialjim.cache4j.local;

import com.asialjim.cache4j.central.InMemoryCentralCache;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.VersionedLookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二级缓存编排集成测试：策略矩阵、版本对账、单飞、空值缓存、降级与指标不变量。
 *
 * @author Asial Jim
 */
class TwoLevelCacheIntegrationTest {

    private static final String NS = "user:detail";

    private final HashMapLocalCacheTest.MutableClock clock = new HashMapLocalCacheTest.MutableClock();

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    private TwoLevelCache<String, String> build(LocalCacheSpec localSpec, CentralCache<String> central) {
        LocalCache<String> local = LocalCaches.create(localSpec, null, clock::nanos);
        return TwoLevelCache.<String, String>builder(NS)
                .local(local)
                .central(central)
                .nanoClock(clock::nanos)
                .milliClock(clock::millis)
                .build();
    }

    private CentralCache<String> central(Duration nullTtl) {
        return new InMemoryCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).nullTtl(nullTtl).build(),
                clock::millis);
    }

    private LocalCacheSpec.VersionSpec fastVersion() {
        return new LocalCacheSpec.VersionSpec(Duration.ofMillis(100), 0D, Duration.ofMillis(50), 1_000);
    }

    // ------------------------------------------------------------------ 策略矩阵

    @Test
    @DisplayName("NONE：不回填本地，每次读都落到中央")
    void noneNeverCachesLocally() {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.noLocal(NS), central(Duration.ZERO));
        cache.put("42", "u42");
        cache.lookup("42");
        cache.lookup("42");

        CacheStats stats = cache.stats();
        assertEquals(0L, stats.localHitCount());
        assertEquals(2L, stats.centralHitCount());
        assertEquals(-1L, cache.local().stats().estimatedSize());
    }

    @Test
    @DisplayName("STATIC：本地优先，命中后不再访问中央")
    void staticHitsLocalFirst() {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()),
                central(Duration.ZERO));
        cache.put("42", "u42");
        cache.lookup("42");
        cache.lookup("42");

        CacheStats stats = cache.stats();
        assertEquals(2L, stats.localHitCount());
        assertEquals(0L, stats.centralHitCount());
    }

    @Test
    @DisplayName("DYNAMIC：冷 key 不落本地，达到阈值后才升热并命中本地")
    void dynamicPromotesAfterThreshold() {
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1),
                fastVersion(), LocalCacheSpec.DynamicSpec.defaults());
        TwoLevelCache<String, String> cache = build(spec, central(Duration.ZERO));
        cache.put("42", "u42");

        // 前 5 次（阈值）都回中央
        for (int i = 0; i < 5; i++) {
            cache.lookup("42");
        }
        assertEquals(5L, cache.stats().centralHitCount());
        assertEquals(0L, cache.stats().localHitCount());

        // 第 6 次已经是热点，本地命中
        cache.lookup("42");
        assertTrue(cache.stats().localHitCount() >= 1L);
    }

    // ------------------------------------------------------------------ 版本对账

    @Test
    @DisplayName("对账：其他节点改写中央后，本地副本在下一轮对账被清除")
    void reconcileDropsStaleCopy() {
        CentralCache<String> central = central(Duration.ZERO);
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()), central);

        cache.put("42", "u42");
        assertEquals("u42", cache.lookup("42").optional().orElseThrow());

        // 另一个节点直接写中央（模拟跨节点变更）
        clock.advance(Duration.ofMillis(1));
        central.putAndGetRevision(key("42"), "u42-new", Duration.ofMinutes(10));

        // 未到对账点：本地仍是旧值
        assertEquals("u42", cache.lookup("42").optional().orElseThrow());

        // 过对账点后，本地副本被清除，重新从中央取到新值
        clock.advance(Duration.ofMillis(200));
        assertEquals("u42-new", cache.lookup("42").optional().orElseThrow());
        assertTrue(cache.stats().versionInvalidationCount() >= 1L);
        assertTrue(cache.stats().versionSyncCount() >= 1L);
    }

    @Test
    @DisplayName("对账：中央删除该 key 后，本地副本被清除（无需 tombstone）")
    void reconcileHandlesRemoteEviction() {
        CentralCache<String> central = central(Duration.ZERO);
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()), central);

        cache.put("42", "u42");
        cache.lookup("42");
        central.evict(key("42"));

        clock.advance(Duration.ofMillis(200));
        assertTrue(cache.lookup("42").isMiss());
        assertTrue(cache.local().keys().isEmpty());
    }

    // ------------------------------------------------------------------ 空值缓存

    @Test
    @DisplayName("nullTtl>0：空值两层都缓存，第二次由本地负命中挡下")
    void negativeCachedInBothLayers() {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()),
                central(Duration.ofSeconds(30)));

        assertTrue(cache.lookup("404", k -> null).isNegative());
        assertTrue(cache.lookup("404").isNegative());

        CacheStats stats = cache.stats();
        // 第一次：未命中 → 回源得 null → 写两层负副本（计 miss + sourceNull，不计负命中）
        assertEquals(1L, stats.missCount());
        assertEquals(1L, stats.sourceNullCount());
        assertEquals(0L, stats.centralNegativeHitCount());
        // 第二次：由本地负副本挡下
        assertEquals(1L, stats.localNegativeHitCount());
    }

    @Test
    @DisplayName("nullTtl=0：放弃防穿透，每次都要回源")
    void withoutNullCachingEveryMissReloads() {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()),
                central(Duration.ZERO));

        assertTrue(cache.lookup("404", k -> null).isMiss());
        assertTrue(cache.lookup("404", k -> null).isMiss());

        CacheStats stats = cache.stats();
        assertEquals(2L, stats.sourceLoadCount());
        assertEquals(2L, stats.sourceNullCount());
        assertEquals(2L, stats.missCount());
        assertEquals(1D, stats.nullLoadRatio(), 1e-9);
    }

    // ------------------------------------------------------------------ 单飞

    @Test
    @DisplayName("单飞：同一 key 的并发回源只执行一次 loader")
    void singleFlightRunsLoaderOnce() throws Exception {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()),
                central(Duration.ZERO));

        AtomicInteger loads = new AtomicInteger();
        CacheLoader<String, String> loader = k -> {
            loads.incrementAndGet();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "loaded";
        };

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    assertEquals("loaded", cache.lookup("42", loader).optional().orElseThrow());
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get());
        assertEquals(1L, cache.stats().sourceLoadCount());
        assertEquals(threads, cache.stats().requestCount(), "单飞的重查不得重复计 request");
    }

    // ------------------------------------------------------------------ 降级与不变量

    @Test
    @DisplayName("failOpen=true：中央故障按未命中降级，不抛给业务")
    void failOpenDegradesGracefully() {
        FailingCentralCache central = new FailingCentralCache(NS, true);
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()), central);

        assertTrue(cache.lookup("42").isMiss());
        cache.evict("42");
        cache.clear();
        assertTrue(cache.stats().centralErrorCount() >= 1L);
    }

    @Test
    @DisplayName("failOpen=false：中央故障直接抛出")
    void failClosedPropagates() {
        FailingCentralCache central = new FailingCentralCache(NS, false);
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()), central);
        assertThrows(com.asialjim.cache4j.core.CacheException.class, () -> cache.lookup("42"));
    }

    @Test
    @DisplayName("指标不变量：四格漏斗之和等于请求数")
    void statsInvariant() {
        TwoLevelCache<String, String> cache = build(
                LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1), fastVersion()),
                central(Duration.ofSeconds(30)));

        cache.put("a", "va");
        cache.lookup("a");                  // 本地命中
        cache.lookup("missing");            // 未命中
        cache.lookup("404", k -> null);     // 回源得到空值 → 负缓存

        CacheStats stats = cache.stats();
        long sum = stats.localHitCount() + stats.localNegativeHitCount()
                + stats.centralHitCount() + stats.centralNegativeHitCount() + stats.missCount();
        assertEquals(stats.requestCount(), sum);
        assertEquals(3L, stats.requestCount());
    }

    /**
     * 一律抛异常的中心缓存，用于降级测试。
     */
    private static final class FailingCentralCache implements CentralCache<String> {

        private final CentralCacheSpec spec;

        private FailingCentralCache(String namespace, boolean failOpen) {
            this.spec = CentralCacheSpec.builder(namespace).ttl(Duration.ofMinutes(1)).failOpen(failOpen).build();
        }

        @Override
        public String name() {
            return spec.namespace();
        }

        private static com.asialjim.cache4j.core.CacheException down() {
            return new com.asialjim.cache4j.core.CacheException(
                    com.asialjim.cache4j.core.CacheResCode.CENTRAL_UNAVAILABLE, "central down");
        }

        @Override
        public Lookup<String> lookup(CacheKey key) {
            throw down();
        }

        @Override
        public Map<CacheKey, Lookup<String>> lookupAll(Collection<CacheKey> keys) {
            throw down();
        }

        @Override
        public VersionedLookup<String> lookupWithRevision(CacheKey key) {
            throw down();
        }

        @Override
        public Map<CacheKey, VersionedLookup<String>> lookupAllWithRevision(Collection<CacheKey> keys) {
            throw down();
        }

        @Override
        public long revision(CacheKey key) {
            throw down();
        }

        @Override
        public Map<CacheKey, Long> revisions(Collection<CacheKey> keys) {
            throw down();
        }

        @Override
        public long putAndGetRevision(CacheKey key, String value, Duration ttl) {
            throw down();
        }

        @Override
        public long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
            throw down();
        }

        @Override
        public OptionalLong putIfAbsentAndGetRevision(CacheKey key, String value, Duration ttl) {
            throw down();
        }

        @Override
        public boolean containsKey(CacheKey key) {
            throw down();
        }

        @Override
        public void put(CacheKey key, String value, Duration ttl) {
            throw down();
        }

        @Override
        public boolean putIfAbsent(CacheKey key, String value, Duration ttl) {
            throw down();
        }

        @Override
        public void evict(CacheKey key) {
            throw down();
        }

        @Override
        public void clear() {
            throw down();
        }

        @Override
        public boolean ping() {
            return false;
        }

        @Override
        public CacheStats stats() {
            return CacheStats.noop();
        }

        @Override
        public CentralCacheSpec spec() {
            return spec;
        }
    }
}
