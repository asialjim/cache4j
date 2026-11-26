package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.VersionedLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 语义层测试（不需要 Redis、不需要任何客户端）。
 *
 * @author Asial Jim
 */
class RedisCentralCacheTest {

    private static final String NS = "user:detail";

    private final MutableClock clock = new MutableClock();
    private InMemoryValueStore<Object> store;
    private CentralCache<String> cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        cache = new RedisCentralCache<>(CentralCacheSpec.builder(NS)
                .ttl(Duration.ofMinutes(10))
                .ttlJitter(0.1D)
                .build().toStrategy(),
                (RedisValueStore<String>) (RedisValueStore<?>) store);
    }

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("写后读：版本与值一并返回")
    void writeThenRead() {
        long revision = cache.putAndGetRevision(key("42"), "u42", Duration.ofMinutes(1));
        assertTrue(revision > 0L);

        VersionedLookup<String> lookup = cache.lookupWithRevision(key("42"));
        assertTrue(lookup.lookup().isHit());
        assertEquals("u42", lookup.lookup().optional().orElseThrow());
        assertEquals(revision, lookup.revision());
    }

    @Test
    @DisplayName("TTL 抖动落在 ±10% 区间内")
    void ttlJitterWithinRange() {
        Duration ttl = Duration.ofSeconds(100);
        cache.putAndGetRevision(key("a"), "va", ttl);
        long expireAt = store.expireAtMillis(key("a"));
        long expected = clock.millis() + ttl.toMillis();
        assertTrue(expireAt >= expected - 10_000L && expireAt <= expected + 10_000L,
                () -> "expireAt=" + expireAt + ", expected≈" + expected);
    }

    @Test
    @DisplayName("空值缓存关闭时，写 null 等价于删除（不保留版本）")
    void nullCachingDisabledDeletesKey() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        long revision = cache.putNegativeAndGetRevision(key("a"), Duration.ofSeconds(30));
        assertEquals(0L, revision);
        assertTrue(cache.lookup(key("a")).isMiss());
        assertEquals(0L, cache.revision(key("a")));
    }

    @Test
    @DisplayName("空值缓存开启时写负副本")
    void nullCachingEnabled() {
        CentralCache<String> withNull = new RedisCentralCache<>(CentralCacheSpec.builder(NS)
                .ttl(Duration.ofMinutes(10))
                .nullTtl(Duration.ofSeconds(30))
                .build().toStrategy(), cast(store));
        long revision = withNull.putNegativeAndGetRevision(key("404"), null);
        assertTrue(revision > 0L);
        assertTrue(withNull.lookup(key("404")).isNegative());
    }

    @Test
    @DisplayName("evict 连版本一起删除：对账时表现为已失效")
    void evictRemovesRevision() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        cache.evict(key("a"));
        assertEquals(0L, cache.revision(key("a")));
        assertTrue(cache.revisions(List.of(key("a"))).isEmpty());
    }

    @Test
    @DisplayName("TTL 到期后既无数据也无版本")
    void expireAfterTtl() {
        cache.putAndGetRevision(key("a"), "va", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        assertTrue(cache.lookup(key("a")).isMiss());
        assertEquals(0L, cache.revision(key("a")));
    }

    @Test
    @DisplayName("putIfAbsent：已存在返回 empty 且版本不变")
    void putIfAbsentKeepsRevision() {
        long first = cache.putAndGetRevision(key("a"), "va", Duration.ofMinutes(1));
        OptionalLong again = cache.putIfAbsentAndGetRevision(key("a"), "vb", Duration.ofMinutes(1));
        assertTrue(again.isEmpty());
        assertEquals(first, cache.revision(key("a")));
        assertEquals("va", cache.lookup(key("a")).optional().orElseThrow());
    }

    @Test
    @DisplayName("适配层异常翻译为 CacheException（UNAVAILABLE / TIMEOUT）")
    void translateStoreFailures() {
        RedisAccessException unavailable = new RedisAccessException(
                RedisAccessException.Kind.UNAVAILABLE, "boom");
        CacheException translated = RedisCentralCache.translate(unavailable);
        assertEquals(CacheResCode.CENTRAL_UNAVAILABLE, translated.resCode());

        RedisAccessException timeout = new RedisAccessException(
                RedisAccessException.Kind.TIMEOUT, "slow");
        assertEquals(CacheResCode.CENTRAL_TIMEOUT, RedisCentralCache.translate(timeout).resCode());
    }

    @Test
    @DisplayName("读失败向上抛 CacheException，不吞异常")
    void readFailurePropagates() {
        RedisValueStore<String> failing = new FailingStore<>();
        CentralCache<String> failingCache = new RedisCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(1)).build().toStrategy(), failing);
        CacheException e = assertThrows(CacheException.class, () -> failingCache.lookup(key("a")));
        assertEquals(CacheResCode.CENTRAL_UNAVAILABLE, e.resCode());
    }

    @Test
    @DisplayName("ping 失败返回 false 而不抛异常")
    void pingFailureIsFalse() {
        CentralCache<String> failingCache = new RedisCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(1)).build().toStrategy(),
                new FailingStore<>());
        assertFalse(failingCache.ping());
    }

    @SuppressWarnings("unchecked")
    private static RedisValueStore<String> cast(InMemoryValueStore<Object> raw) {
        return (RedisValueStore<String>) (RedisValueStore<?>) raw;
    }

    /**
     * 可推进的测试时钟。
     */
    static final class MutableClock {

        private long millis = 1_700_000_000_000L;

        long millis() {
            return millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }
    }

    /**
     * 一律抛连接的 store，用于异常翻译测试。
     */
    private static final class FailingStore<V> implements RedisValueStore<V> {

        @Override
        public VersionedLookup<V> read(CacheKey key) {
            throw down();
        }

        @Override
        public java.util.Map<CacheKey, VersionedLookup<V>> readAll(java.util.Collection<CacheKey> keys) {
            throw down();
        }

        @Override
        public long write(CacheKey key, V value, Duration ttl) {
            throw down();
        }

        @Override
        public long writeNegative(CacheKey key, Duration ttl) {
            throw down();
        }

        @Override
        public OptionalLong writeIfAbsent(CacheKey key, V value, Duration ttl) {
            throw down();
        }

        @Override
        public void delete(CacheKey key) {
            throw down();
        }

        @Override
        public void clear(String namespace) {
            throw down();
        }

        @Override
        public java.util.Map<CacheKey, Long> revisions(java.util.Collection<CacheKey> keys) {
            throw down();
        }

        @Override
        public long revision(CacheKey key) {
            throw down();
        }

        @Override
        public com.asialjim.cache4j.core.central.HotKeyReport reportHotKey(
                String namespace, CacheKey key, long delta) {
            throw down();
        }

        @Override
        public java.util.Map<CacheKey, com.asialjim.cache4j.core.central.HotKeyReport> reportHotKeyAll(
                String namespace, java.util.Map<CacheKey, Long> deltas) {
            throw down();
        }

        @Override
        public boolean ping() {
            throw down();
        }

        @Override
        public void close() {
        }

        private static RedisAccessException down() {
            return new RedisAccessException(RedisAccessException.Kind.UNAVAILABLE, "central down");
        }
    }
}
