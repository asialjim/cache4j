package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.local.NoneLocalCache;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.VersionedLocalCache;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    protected abstract VersionedLocalCache<String> newCache(long maxSize);

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
        VersionedLocalCache<String> cache = newCache(100);
        // 带版本写入走 putIfNewer（版本来自中央）；普通 put 不携带版本
        cache.putIfNewer(key("42"), "u42", 7L, Duration.ofSeconds(10));

        LocalEntry<String> entry = cache.entryOf(key("42"));
        assertEquals("u42", entry.value(), implementation());
        assertEquals(7L, entry.revision());
        assertFalse(entry.isNegative());
    }

    @Test
    @DisplayName("负副本可读回，不会被当成 Miss")
    void negativeEntryIsReadable() {
        VersionedLocalCache<String> cache = newCache(100);
        cache.put(key("404"), null, Duration.ofSeconds(10));

        assertTrue(cache.entryOf(key("404")).isNegative(), implementation());
        assertTrue(cache.containsKey(key("404")));
    }

    @Test
    @DisplayName("TTL 到期后不可见")
    void shouldExpire() {
        VersionedLocalCache<String> cache = newCache(100);
        cache.put(key("42"), "u42", Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(1_500));
        assertTrue(cache.lookup(key("42")).isMiss(), implementation());
        assertTrue(cache.keys().isEmpty());
    }

    @Test
    @DisplayName("不超过容量上限（允许分段实现的少量超发）")
    void shouldRespectCapacity() {
        long maxSize = 16L;
        VersionedLocalCache<String> cache = newCache(maxSize);
        for (int i = 0; i < 200; i++) {
            String value = "v" + i;
            cache.put(key(value), value, Duration.ofMinutes(1));
        }
        // 契约：容量上限在实现完成一次维护后生效（异步淘汰实现如 Caffeine 需要这一步）
        cache.maintain();
    }

    @Test
    @DisplayName("putIfAbsent 并发只有一个成功")
    void putIfAbsentIsAtomic() throws Exception {
        VersionedLocalCache<String> cache = newCache(1_000);
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
                    if (cache.putIfAbsent(key("k"), "v", Duration.ofMinutes(1))) {
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
        VersionedLocalCache<String> cache = newCache(100);
        cache.put(key("a"), "va", Duration.ofMinutes(1));
        cache.put(key("b"), "vb", Duration.ofMinutes(1));

        cache.evict(key("a"));
        assertTrue(cache.lookup(key("a")).isMiss(), implementation());

        cache.clear();
        assertTrue(cache.keys().isEmpty(), implementation());
    }

    @Test
    @DisplayName("keys() 返回当前快照")
    void keysSnapshot() {
        VersionedLocalCache<String> cache = newCache(100);
        cache.put(key("a"), "va", Duration.ofMinutes(1));
        cache.put(key("b"), "vb", Duration.ofMinutes(1));

        assertEquals(2, cache.keys().size(), implementation());
        assertTrue(cache.keys().contains(key("a")));
    }

    @Test
    @DisplayName("STATIC 的 access 恒为 true（不记频）")
    void accessDoesNotGateStatic() {
        VersionedLocalCache<String> cache = newCache(100);
        assertTrue(cache.access(key("a")), implementation());
        assertTrue(cache.available());
        assertEquals(com.asialjim.cache4j.core.local.LocalStrategy.STATIC, cache.strategy());
    }

    @Test
    @DisplayName("STATIC 接受任意 key 的写入（accepts 恒为 true）")
    void acceptsAnyKeyForStatic() {
        VersionedLocalCache<String> cache = newCache(100);
        assertTrue(cache.accepts(key("a")), implementation());
    }

    @Test
    @DisplayName("显式失效会通知条目消失")
    void explicitEvictNotifiesRemoval() {
        VersionedLocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), "va", Duration.ofMinutes(1));
        cache.evict(key("a"));

        assertEquals(1, removed.size(), implementation());
        assertEquals(key("a"), removed.get(0), implementation());
    }

    @Test
    @DisplayName("TTL 过期会通知条目消失（惰性触发也算）")
    void expiredEntryNotifiesRemoval() {
        VersionedLocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), "va", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        cache.entryOf(key("a"));                       // 惰性过期在这里被发现

        assertEquals(1, removed.size(), implementation());
        assertEquals(key("a"), removed.get(0), implementation());
    }

    @Test
    @DisplayName("覆盖写不算条目消失，不通知")
    void overwriteDoesNotNotifyRemoval() {
        VersionedLocalCache<String> cache = newCache(100);
        List<CacheKey> removed = new CopyOnWriteArrayList<>();
        cache.onEntryRemoved(removed::add);

        cache.put(key("a"), "v1", Duration.ofMinutes(1));
        cache.put(key("a"), "v2", Duration.ofMinutes(1));

        assertTrue(removed.isEmpty(), implementation() + "：覆盖写不应触发条目消失通知");
    }

    @Test
    @DisplayName("putIfNewer：本地已有更新版本时拒绝覆盖，同版本允许刷新")
    void putIfNewerKeepsNewerRevision() {
        VersionedLocalCache<String> cache = newCache(100);
        assertTrue(cache.putIfNewer(key("a"), "v2", 2L, Duration.ofMinutes(1)),
                implementation());
        assertFalse(cache.putIfNewer(key("a"), "v1", 1L, Duration.ofMinutes(1)),
                implementation() + "：旧版本不得覆盖新版本");
        assertEquals("v2", cache.entryOf(key("a")).value());

        assertTrue(cache.putIfNewer(key("a"), "v2b", 2L, Duration.ofMinutes(1)),
                implementation() + "：同版本允许写入（刷新 TTL）");
        assertEquals("v2b", cache.entryOf(key("a")).value());
    }

    @Test
    @DisplayName("entryOf 是内部读：取条目（含版本），未命中返回 null")
    void peekDoesNotCountStoreStats() {
        VersionedLocalCache<String> cache = newCache(100);
        cache.put(key("a"), "va", Duration.ofMinutes(1));


        assertNotNull(cache.entryOf(key("a")), implementation());
        assertNull(cache.entryOf(key("absent")), implementation());

    }

    @Test
    @DisplayName("NONE 策略：不存值、不参与版本对账")
    void noOpHasNoState() {
        LocalCache<String> cache = new NoneLocalCache<>(LocalCacheSpec.noLocal("user:detail"));
        assertFalse(cache.available());

        cache.put(key("a"), "va", Duration.ofMinutes(1));
        assertEquals(Lookup.Miss.class, cache.lookup(key("a")).getClass());
        // NONE 不在 VersionedLocalCache 层次里：没有 keys()、没有版本表、没有对账——
        // 它连"持有 key"这个概念都不需要表达
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
