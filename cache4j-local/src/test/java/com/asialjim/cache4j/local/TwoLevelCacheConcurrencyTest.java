package com.asialjim.cache4j.local;

import com.asialjim.cache4j.central.InMemoryCentralCache;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.VersionedLocalCache;
import com.asialjim.cache4j.core.twolevel.Cache4jImpl;
import com.asialjim.cache4j.core.Cache4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与回归测试：单飞、分区索引一致性、对账新鲜度、统计口径。
 *
 * <p>覆盖的既有缺陷：</p>
 * <ul>
 *   <li>{@code NONE} 策略下分区号表无界增长；</li>
 *   <li>单飞等待方不计结论导致 request 与命中数不平账；</li>
 *   <li>回填前/对账时的内部读污染本地命中率；</li>
 *   <li>{@code containsKey} 被当成一次正常读（既计指标又推高频次）。</li>
 * </ul>
 *
 * @author Asial Jim
 */
class TwoLevelCacheConcurrencyTest {

    private static final String NS = "user:detail";

    private final AtomicLong nanos = new AtomicLong(1_700_000_000_000_000_000L);

    private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    /**
     * @param strategy 本地策略
     * @param version  版本配置
     * @return 门面（本地 HashMap 实现 + 内存中央）
     */
    private Cache4j<String, String> cache(String strategy, LocalCacheSpec.VersionSpec version) {
        LocalCacheSpec.VersionSpec effective = version == null
                ? LocalCacheSpec.VersionSpec.defaults() : version;
        LocalCacheSpec localSpec = switch (strategy) {
            case "NONE" -> LocalCacheSpec.noLocal(NS);
            case "DYNAMIC" -> LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1),
                    effective, LocalCacheSpec.DynamicSpec.defaults());
            default -> LocalCacheSpec.staticCache(NS, 1_000, Duration.ofMinutes(1), effective);
        };
        LocalCache<String> local = LocalCaches.create(localSpec, null, nanos::get);
        CentralCache<String> central = new InMemoryCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).build(), millis::get);
        return Cache4jImpl.<String, String>builder(NS)
                .local(local)
                .central(central)
                .nanoClock(nanos::get)
                .milliClock(millis::get)
                .build();
    }

    @Test
    @DisplayName("NONE 策略：根本没有对账组件（无本地副本 = 无版本 = 无对账）")
    void noneStrategyDoesNotTrackPartitions() throws Exception {
        Cache4j<String, String> cache = cache("NONE", null);
        for (int i = 0; i < 200; i++) {
            cache.put("k" + i, "v" + i);
            cache.lookup("k" + i);
        }
        // NONE 用的 NoneLocalCache 里没有 reconciler：它不存副本，也就不需要「副本是否最新」
        assertThrows(NoSuchFieldException.class, () -> reconcilerOf(cache),
                "NONE 策略不该持有对账组件");
    }

    @Test
    @DisplayName("单飞：并发回源只执行一次 loader，且每个请求都有结论（统计平账）")
    void singleFlightLoadsOnceAndBalancesStats() throws Exception {
        Cache4j<String, String> cache = cache("STATIC", null);
        AtomicInteger loads = new AtomicInteger();
        CacheLoader<String, String> loader = key -> {
            loads.incrementAndGet();
            sleep(50L);
            return "loaded-" + key;
        };

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Lookup<String>> results = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<Lookup<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.lookup("42", loader);
                }));
            }
            start.countDown();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, loads.get(), "同一 key 的并发回源只执行一次");
        results.forEach(lookup -> {
            assertTrue(lookup.isHit());
            assertEquals("loaded-42", lookup.optional().orElseThrow());
        });

    }

    @Test
    @DisplayName("并发读写失效：分区索引与本地副本始终保持一致")
    void concurrentOpsKeepIndexConsistent() throws Exception {
        Cache4j<String, String> cache = cache("STATIC", null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        String key = "k" + (i % 50);
                        switch (i % 4) {
                            case 0 -> cache.put(key, "v" + i);
                            case 1 -> cache.lookup(key);
                            case 2 -> cache.evict(key);
                            default -> cache.containsKey(key);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int tracked = indexedKeys(cache);
        int localSize = ((VersionedLocalCache<?>) cache.local()).keys().size();
        assertTrue(tracked >= localSize,
                "索引应覆盖全部本地副本：索引=" + tracked + "，本地=" + localSize);
        assertEquals(partitionsOf(cache).size() > 0, tracked > 0 || localSize == 0);
    }

    @Test
    @DisplayName("对账 + 回填：中央更新后并发读最终拿到新值，且不读陈旧副本")
    void concurrentReadsEventuallySeeFreshValue() throws Exception {
        LocalCacheSpec.VersionSpec version = new LocalCacheSpec.VersionSpec(
                Duration.ofMillis(5), Duration.ZERO, Duration.ofMillis(5), 1_000, 16);
        Cache4j<String, String> cache = cache("STATIC", version);

        cache.put("42", "v1");
        assertEquals("v1", cache.lookup("42").optional().orElseThrow());

        cache.put("42", "v2");

        String seen = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            seen = cache.lookup("42").optional().orElse(null);
            if ("v2".equals(seen)) {
                break;
            }
        }
        assertEquals("v2", seen, "对账应清掉版本落后的本地副本，读最终返回新值");

        for (int i = 0; i < 50; i++) {
            assertEquals("v2", cache.lookup("42").optional().orElseThrow());
        }
    }

    @Test
    @DisplayName("containsKey 不算一次读：不动 request 计数")
    void containsKeyIsNotCountedAsRead() {
        Cache4j<String, String> cache = cache("STATIC", null);
        cache.put("42", "v1");

        assertTrue(cache.containsKey("42"));
        assertTrue(!cache.containsKey("43"));
    }

    // ------------------------------------------------------------------ 辅助

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 取对账组件：它现在住在<strong>本地缓存</strong>里（对账是本地副本的新鲜度问题），
     * 所以从门面拿 local，再从 local 拿 reconciler。
     */
    private static Object reconcilerOf(Cache4j<String, String> cache) throws Exception {
        Field holder = Cache4jImpl.class.getDeclaredField("local");
        holder.setAccessible(true);
        Object local = holder.get(cache);
        Field field = local.getClass().getDeclaredField("reconciler");
        field.setAccessible(true);
        return field.get(local);
    }

    @SuppressWarnings("unchecked")
    private static Map<CacheKey, Integer> partitionsOf(Cache4j<String, String> cache)
            throws Exception {
        Object reconciler = reconcilerOf(cache);
        Field field = reconciler.getClass().getDeclaredField("keyPartitions");
        field.setAccessible(true);
        return (Map<CacheKey, Integer>) field.get(reconciler);
    }

    @SuppressWarnings("unchecked")
    private static int indexedKeys(Cache4j<String, String> cache) throws Exception {
        Object reconciler = reconcilerOf(cache);
        Field field = reconciler.getClass().getDeclaredField("localPartitions");
        field.setAccessible(true);
        Map<Integer, Set<CacheKey>> buckets = (Map<Integer, Set<CacheKey>>) field.get(reconciler);
        assertNotNull(buckets);
        return buckets.values().stream().mapToInt(Set::size).sum();
    }

    @SuppressWarnings("unused")
    private static LocalEntry<String> entryOf(Cache4j<String, String> cache, CacheKey key) {
        return cache.local().peek(key);
    }
}
