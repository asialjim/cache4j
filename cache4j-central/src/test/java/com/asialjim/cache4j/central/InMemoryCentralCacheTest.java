package com.asialjim.cache4j.central;

import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.VersionedLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存中央缓存契约测试。
 *
 * @author Asial Jim
 */
class InMemoryCentralCacheTest {

    private final MutableClock clock = new MutableClock();
    private CentralCache<String> cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryCentralCache<>(
                CacheStrategy.builder("user:detail").ttl(Duration.ofMinutes(10)).build(),
                clock::millis);
    }

    private CacheKey key(String value) {
        return new CacheKey("user:detail", "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("写后读：版本与值一并返回")
    void shouldReadWithRevision() {
        long revision = cache.putAndGetRevision(key("42"), "u42", Duration.ofMinutes(1));
        assertTrue(revision > 0L);

        VersionedLookup<String> lookup = cache.lookupWithRevision(key("42"));
        assertTrue(lookup.lookup().isHit());
        assertEquals("u42", lookup.lookup().optional().orElseThrow());
        assertEquals(revision, lookup.revision());
    }

    @Test
    @DisplayName("未命中的 key 版本为 0")
    void missShouldHaveZeroRevision() {
        VersionedLookup<String> lookup = cache.lookupWithRevision(key("404"));
        assertTrue(lookup.lookup().isMiss());
        assertEquals(0L, lookup.revision());
    }

    @Test
    @DisplayName("同一毫秒连续两次写也必须产生不同版本")
    void revisionsMustDiffer() {
        long first = cache.putAndGetRevision(key("42"), "v1", Duration.ofMinutes(1));
        long second = cache.putAndGetRevision(key("42"), "v2", Duration.ofMinutes(1));
        assertTrue(second > first, () -> first + " -> " + second);
    }

    @Test
    @DisplayName("不同 key 的版本互不影响")
    void revisionsAreIndependent() {
        long revisionA = cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        clock.advance(Duration.ofMillis(1));
        long revisionB = cache.putAndGetRevision(key("b"), "vb", Duration.ofMinutes(1));
        // 每个 key 各自维护版本：互不覆盖，各自返回自己最后一次写入的版本
        assertEquals(revisionA, cache.revision(key("a")));
        assertEquals(revisionB, cache.revision(key("b")));
        assertTrue(revisionB > revisionA);
        // 写 b 不会改变 a 的版本
        cache.putAndGetRevision(key("b"), "vb2", Duration.ofMinutes(1));
        assertEquals(revisionA, cache.revision(key("a")));
    }

    @Test
    @DisplayName("空值缓存：负命中且计入版本")
    void shouldCacheNegative() {
        long revision = cache.putNegativeAndGetRevision(key("404"), Duration.ofSeconds(30));
        VersionedLookup<String> lookup = cache.lookupWithRevision(key("404"));
        assertTrue(lookup.lookup().isNegative());
        assertEquals(revision, lookup.revision());
    }

    @Test
    @DisplayName("TTL 到期后既无数据也无版本")
    void shouldExpire() {
        cache.putAndGetRevision(key("42"), "v", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        assertTrue(cache.lookupWithRevision(key("42")).lookup().isMiss());
        assertEquals(0L, cache.revision(key("42")));
    }

    @Test
    @DisplayName("evict 连同版本一起删除：对账时表现为“中央已无此 key”")
    void evictRemovesRevision() {
        cache.putAndGetRevision(key("42"), "v", Duration.ofMinutes(1));
        cache.evict(key("42"));
        assertEquals(0L, cache.revision(key("42")));
        assertTrue(cache.revisions(List.of(key("42"))).isEmpty());
    }

    @Test
    @DisplayName("revisions 只返回仍然有效的 key")
    void revisionsOnlyLiveKeys() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        cache.putAndGetRevision(key("b"), "vb", Duration.ofMinutes(1));
        Map<CacheKey, Long> revisions = cache.revisions(List.of(key("a"), key("b"), key("c")));
        assertEquals(2, revisions.size());
        assertFalse(revisions.containsKey(key("c")));
    }

    @Test
    @DisplayName("putIfAbsent：只有一个成功，且失败不更新版本")
    void putIfAbsentIsAtomic() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                String value = "v" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    OptionalLong revision = cache.putIfAbsentAndGetRevision(
                            key("hot"), value, Duration.ofMinutes(1));
                    if (revision.isPresent()) {
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

        long revisionBefore = cache.revision(key("hot"));
        OptionalLong again = cache.putIfAbsentAndGetRevision(key("hot"), "other", Duration.ofMinutes(1));
        assertTrue(again.isEmpty());
        assertEquals(revisionBefore, cache.revision(key("hot")));
    }

    @Test
    @DisplayName("clear 后全部失效")
    void clearShouldDropAll() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        cache.putNegativeAndGetRevision(key("b"), Duration.ofSeconds(10));
        cache.clear();
        assertTrue(cache.lookupWithRevision(key("a")).lookup().isMiss());
        assertEquals(0L, cache.revision(key("b")));
    }

    @Test
    @DisplayName("ping 恒为可用")
    void pingIsAlwaysUp() {
        assertTrue(cache.ping());
    }

    @Test
    @DisplayName("批量读保持 key 对应关系")
    void lookupAllKeepsKeys() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        Map<CacheKey, Lookup<String>> result = cache.lookupAll(List.of(key("a"), key("b")));
        assertTrue(result.get(key("a")).isHit());
        assertTrue(result.get(key("b")).isMiss());
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
