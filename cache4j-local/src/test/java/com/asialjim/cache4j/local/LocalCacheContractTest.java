package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalCache} 契约测试：所有存储实现（HashMap / Caffeine / …）都必须通过同一套。
 *
 * <p>实现方只需提供 {@link #newCache(long)}；本类里的用例只断言契约语义，
 * 不依赖具体淘汰算法。</p>
 *
 * @author Asial Jim
 */
public abstract class LocalCacheContractTest {

    /**
     * 可推进的测试时钟。
     */
    protected final MutableClock clock = new MutableClock();

    /**
     * 由实现方提供：容量为 {@code maxSize} 的本地缓存（{@code STATIC} 策略）。
     *
     * @param maxSize 条目上限
     * @return 本地缓存
     */
    protected abstract LocalCache<String> newCache(long maxSize);

    /**
     * 实现名（用于失败信息）。
     *
     * @return 名称
     */
    protected abstract String implementation();

    /**
     * @return 测试用 key
     */
    protected CacheKey key(String value) {
        return new CacheKey("user:detail", "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("写入后可读，条目携带版本")
    void putAndLookup() {
        LocalCache<String> cache = newCache(100);
        cache.put(key("42"), LocalEntry.hit("u42", 7L), Duration.ofSeconds(10));

        LocalEntry<String> entry = cache.entry(key("42"));
        assertEquals("u42", entry.value(), implementation());
        assertEquals(7L, entry.revision());
        assertFalse(entry.isNegative());
    }

    @Test
    @DisplayName("负副本可读回，不会被当成 Miss")
    void negativeEntryIsReadable() {
        LocalCache<String> cache = newCache(100);
        cache.put(key("404"), LocalEntry.negative(9L), Duration.ofSeconds(10));

        assertTrue(cache.entry(key("404")).isNegative(), implementation());
        assertTrue(cache.containsKey(key("404")));
    }

    @Test
    @DisplayName("TTL 到期后不可见")
    void shouldExpire() {
        LocalCache<String> cache = newCache(100);
        cache.put(key("42"), LocalEntry.hit("u42", 1L), Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(1_500));
        assertTrue(cache.lookup(key("42")).isMiss(), implementation());
        assertTrue(cache.keys().isEmpty());
    }

    @Test
    @DisplayName("不超过容量上限（允许分段实现的少量超发）")
    void shouldRespectCapacity() {
        long maxSize = 16L;
        LocalCache<String> cache = newCache(maxSize);
        for (int i = 0; i < 200; i++) {
            String value = "v" + i;
            cache.put(key(value), LocalEntry.hit(value, i), Duration.ofMinutes(1));
        }
        // 契约：容量上限在实现完成一次维护后生效（异步淘汰实现如 Caffeine 需要这一步）
        cache.maintain();
        assertTrue(cache.stats().estimatedSize() <= maxSize * 2,
                () -> implementation() + " 估计条目数 " + cache.stats().estimatedSize() + " 超出上限两倍");
    }

    @Test
    @DisplayName("putIfAbsent 并发只有一个成功")
    void putIfAbsentIsAtomic() throws Exception {
        LocalCache<String> cache = newCache(1_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (cache.putIfAbsent(key("k"), LocalEntry.hit("v", 1L), Duration.ofMinutes(1))) {
                        success.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get(), implementation());
    }

    @Test
    @DisplayName("evict / clear 生效")
    void evictAndClear() {
        LocalCache<String> cache = newCache(100);
        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        cache.put(key("b"), LocalEntry.hit("vb", 2L), Duration.ofMinutes(1));

        cache.evict(key("a"));
        assertTrue(cache.lookup(key("a")).isMiss(), implementation());

        cache.clear();
        assertTrue(cache.keys().isEmpty(), implementation());
    }

    @Test
    @DisplayName("keys() 返回当前快照")
    void keysSnapshot() {
        LocalCache<String> cache = newCache(100);
        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        cache.put(key("b"), LocalEntry.hit("vb", 2L), Duration.ofMinutes(1));

        assertEquals(2, cache.keys().size(), implementation());
        assertTrue(cache.keys().contains(key("a")));
    }

    @Test
    @DisplayName("STATIC 的 access 恒为 true（不记频）")
    void accessDoesNotGateStatic() {
        LocalCache<String> cache = newCache(100);
        assertTrue(cache.access(key("a")), implementation());
        assertTrue(cache.available());
        assertEquals(com.asialjim.cache4j.core.local.LocalStrategy.STATIC, cache.strategy());
    }

    @Test
    @DisplayName("STATIC 接受任意 key 的写入（accepts 恒为 true）")
    void acceptsAnyKeyForStatic() {
        LocalCache<String> cache = newCache(100);
        assertTrue(cache.accepts(key("a")), implementation());
    }

    @Test
    @DisplayName("显式失效会通知条目消失")
    void explicitEvictNotifiesRemoval() {
        LocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        cache.evict(key("a"));

        assertEquals(1, removed.size(), implementation());
        assertEquals(key("a"), removed.get(0), implementation());
    }

    @Test
    @DisplayName("TTL 过期会通知条目消失（惰性触发也算）")
    void expiredEntryNotifiesRemoval() {
        LocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        cache.entry(key("a"));                       // 惰性过期在这里被发现

        assertEquals(1, removed.size(), implementation());
        assertEquals(key("a"), removed.get(0), implementation());
    }

    @Test
    @DisplayName("覆盖写不算条目消失，不通知")
    void overwriteDoesNotNotifyRemoval() {
        LocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), LocalEntry.hit("v1", 1L), Duration.ofMinutes(1));
        cache.put(key("a"), LocalEntry.hit("v2", 2L), Duration.ofMinutes(1));

        assertTrue(removed.isEmpty(), implementation() + "：覆盖写不应触发条目消失通知");
    }

    @Test
    @DisplayName("NONE 策略：不存值、不参与版本对账")
    void noOpHasNoState() {
        LocalCache<String> cache = new NoOpLocalCache<>(LocalCacheSpec.noLocal("user:detail"));
        assertFalse(cache.available());

        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        assertEquals(Lookup.Miss.class, cache.lookup(key("a")).getClass());
        assertTrue(cache.keys().isEmpty());
        assertEquals(-1L, cache.stats().estimatedSize());
    }

    /**
     * 可推进的测试时钟（纳秒 + 毫秒同源）。
     */
    public static final class MutableClock {

        private long nanos = 1_700_000_000_000_000_000L;
        private long millis = 1_700_000_000_000L;

        /**
         * @return 当前纳秒
         */
        public long nanos() {
            return nanos;
        }

        /**
         * @return 当前毫秒
         */
        public long millis() {
            return millis;
        }

        /**
         * 推进时钟。
         *
         * @param duration 时长
         */
        public void advance(Duration duration) {
            nanos += duration.toNanos();
            millis += duration.toMillis();
        }
    }
}
