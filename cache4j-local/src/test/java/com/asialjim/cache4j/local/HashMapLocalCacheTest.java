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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HashMap 本地缓存契约测试。
 *
 * @author Asial Jim
 */
class HashMapLocalCacheTest {

    private final MutableClock clock = new MutableClock();

    private LocalCache<String> cache(long maxSize) {
        LocalCacheSpec spec = LocalCacheSpec.staticCache("user:detail", maxSize, Duration.ofSeconds(30));
        return LocalCaches.create(spec, null, clock::nanos);
    }

    private CacheKey key(String value) {
        return new CacheKey("user:detail", "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("写入后可读，条目携带版本")
    void putAndLookup() {
        LocalCache<String> cache = cache(100);
        cache.put(key("42"), LocalEntry.hit("u42", 7L), Duration.ofSeconds(10));
        LocalEntry<String> entry = cache.entry(key("42"));
        assertEquals("u42", entry.value());
        assertEquals(7L, entry.revision());
        assertFalse(entry.isNegative());
    }

    @Test
    @DisplayName("负副本可读回，不会被当成 Miss")
    void negativeEntryIsReadable() {
        LocalCache<String> cache = cache(100);
        cache.put(key("404"), LocalEntry.negative(9L), Duration.ofSeconds(10));
        assertTrue(cache.entry(key("404")).isNegative());
        assertTrue(cache.containsKey(key("404")));
    }

    @Test
    @DisplayName("TTL 到期后不可见")
    void shouldExpire() {
        LocalCache<String> cache = cache(100);
        cache.put(key("42"), LocalEntry.hit("u42", 1L), Duration.ofSeconds(1));
        clock.advance(Duration.ofMillis(1_500));
        assertTrue(cache.lookup(key("42")).isMiss());
        assertTrue(cache.keys().isEmpty());
    }

    @Test
    @DisplayName("超过容量后淘汰最久未访问的条目（分段 LRU）")
    void shouldEvictByCapacity() {
        LocalCache<String> cache = cache(16);
        for (int i = 0; i < 200; i++) {
            String value = "v" + i;
            cache.put(key(value), LocalEntry.hit(value, i), Duration.ofMinutes(1));
        }
        assertTrue(cache.stats().estimatedSize() <= 32, "分段允许略超，但不应无界增长");
    }

    @Test
    @DisplayName("putIfAbsent 并发只有一个成功")
    void putIfAbsentIsAtomic() throws Exception {
        LocalCache<String> cache = cache(1000);
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
        assertEquals(1, success.get());
    }

    @Test
    @DisplayName("evict / clear 生效")
    void evictAndClear() {
        LocalCache<String> cache = cache(100);
        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        cache.put(key("b"), LocalEntry.hit("vb", 2L), Duration.ofMinutes(1));
        cache.evict(key("a"));
        assertTrue(cache.lookup(key("a")).isMiss());
        cache.clear();
        assertTrue(cache.keys().isEmpty());
    }

    @Test
    @DisplayName("keys() 返回当前快照")
    void keysSnapshot() {
        LocalCache<String> cache = cache(100);
        cache.put(key("a"), LocalEntry.hit("va", 1L), Duration.ofMinutes(1));
        cache.put(key("b"), LocalEntry.hit("vb", 2L), Duration.ofMinutes(1));
        assertEquals(List.of(key("a"), key("b")).size(), cache.keys().size());
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
     * 可推进的测试时钟。
     */
    static final class MutableClock {

        private long nanos = 1_700_000_000_000_000_000L;
        private long millis = 1_700_000_000_000L;

        long nanos() {
            return nanos;
        }

        long millis() {
            return millis;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
            millis += duration.toMillis();
        }
    }
}
