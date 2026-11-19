package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 基于 Caffeine 的本地存储实现。
 *
 * <p>与 HashMap 实现相比：全局 LRU（Window TinyLFU）淘汰、每条目独立 TTL、命中率/淘汰统计更准；
 * 代价是引入 Caffeine 依赖，所以只放在 {@code cache4j-local-caffeine}。</p>
 *
 * <p>它<strong>不改变</strong>策略语义：{@code DYNAMIC} 的热点门控仍由
 * {@code DynamicLocalCache} 装饰器完成，Caffeine 只回答"容量满时淘汰谁"。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class CaffeineLocalCache<V> implements LocalCache<V> {

    /**
     * 永不过期的 TTL 表达（Caffeine 用纳秒计时）。
     */
    private static final long NEVER = Long.MAX_VALUE;

    private final LocalCacheSpec spec;
    private final com.github.benmanes.caffeine.cache.Cache<CacheKey, TimedEntry<V>> cache;
    private final CacheCounters counters = new CacheCounters();

    /**
     * @param spec      配置
     * @param nanoClock 单调时钟（同时作为 Caffeine 的 ticker，便于测试注入）
     */
    public CaffeineLocalCache(LocalCacheSpec spec, LongSupplier nanoClock) {
        this.spec = spec;
        Ticker ticker = nanoClock::getAsLong;
        this.cache = Caffeine.newBuilder()
                .maximumSize(spec.maxSize())
                .ticker(ticker)
                .expireAfter(new Expiry<CacheKey, TimedEntry<V>>() {
                    @Override
                    public long expireAfterCreate(CacheKey key, TimedEntry<V> value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(CacheKey key, TimedEntry<V> value,
                                                  long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(CacheKey key, TimedEntry<V> value,
                                                long currentTime, long currentDuration) {
                        // v1 只支持写后过期：读不续期
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();
    }

    @Override
    public LocalStrategy strategy() {
        return spec.strategy();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        TimedEntry<V> timed = cache.getIfPresent(key);
        if (timed == null) {
            counters.increment(CacheCounters.Counter.STORE_MISS);
            return Lookup.miss();
        }
        counters.increment(CacheCounters.Counter.STORE_HIT);
        return Lookup.hit(timed.entry());
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        cache.put(key, new TimedEntry<>(value, toNanos(ttl)));
        counters.increment(CacheCounters.Counter.PUT);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        TimedEntry<V> previous = cache.asMap().putIfAbsent(key, new TimedEntry<>(value, toNanos(ttl)));
        if (previous != null) {
            return false;
        }
        counters.increment(CacheCounters.Counter.PUT);
        return true;
    }

    @Override
    public void evict(CacheKey key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public Set<CacheKey> keys() {
        return new HashSet<>(cache.asMap().keySet());
    }

    @Override
    public boolean access(CacheKey key) {
        // STATIC 不记频；DYNAMIC 由装饰器负责
        return true;
    }

    @Override
    public void maintain() {
        cache.cleanUp();
    }

    @Override
    public CacheStats stats() {
        counters.setEstimatedSize(cache.estimatedSize());
        return counters;
    }

    @Override
    public void close() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    private static long toNanos(Duration ttl) {
        return ttl == null ? NEVER : Math.max(1L, ttl.toNanos());
    }

    /**
     * 带 TTL 的条目包装：Caffeine 的 {@link Expiry} 从 value 里读每条目的存活时间。
     *
     * @param entry   本地副本
     * @param ttlNanos 存活纳秒数；{@link Long#MAX_VALUE} 表示不过期
     * @param <V>     值类型
     */
    private record TimedEntry<V>(LocalEntry<V> entry, long ttlNanos) {
    }
}
