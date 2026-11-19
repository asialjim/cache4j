package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 零依赖本地缓存：分段 LRU + 写后过期。
 *
 * <p>结构：16 个段，每段一把锁 + 一个 {@code accessOrder} 的 {@link LinkedHashMap}，
 * {@code maxSize} 按段均分（允许因分段略超）。读时惰性删除过期项。</p>
 *
 * <p>取舍：分段 LRU 不是全局 LRU，命中率略低，但锁竞争小、行为可预测、零第三方依赖；
 * 生产高并发场景建议换 Caffeine 实现。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class HashMapLocalCache<V> implements LocalCache<V> {

    private static final int SEGMENTS = 16;

    private final LocalCacheSpec spec;
    private final Segment<V>[] segments;
    private final int perSegmentCapacity;
    private final LongSupplier nanoClock;
    private final CacheCounters counters = new CacheCounters();

    /**
     * 条目消失（过期 / 容量淘汰 / 显式失效）时的通知，默认空实现。
     */
    private volatile Consumer<CacheKey> removalListener = key -> {
    };

    /**
     * @param spec      配置（策略为 {@code STATIC} 或 {@code DYNAMIC}）
     * @param nanoClock 单调时钟
     */
    @SuppressWarnings("unchecked")
    public HashMapLocalCache(LocalCacheSpec spec, LongSupplier nanoClock) {
        this.spec = spec;
        this.nanoClock = nanoClock;
        this.perSegmentCapacity = (int) Math.max(1L, (spec.maxSize() + SEGMENTS - 1) / SEGMENTS);
        this.segments = new Segment[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            segments[i] = new Segment<>();
        }
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
        Segment<V> segment = segmentOf(key);
        long now = nanoClock.getAsLong();
        boolean expired = false;
        segment.lock.lock();
        try {
            TimedEntry<V> entry = segment.map.get(key);
            if (entry == null) {
                counters.increment(CacheCounters.Counter.STORE_MISS);
                return Lookup.miss();
            }
            if (entry.expired(now)) {
                segment.map.remove(key);
                counters.increment(CacheCounters.Counter.STORE_MISS);
                expired = true;
                return Lookup.miss();
            }
            counters.increment(CacheCounters.Counter.STORE_HIT);
            return Lookup.hit(entry.entry);
        } finally {
            segment.lock.unlock();
            if (expired) {
                fireRemoved(key);
            }
        }
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return lookup(key).isHit();
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        long now = nanoClock.getAsLong();
        long expireAt = ttl == null ? Long.MAX_VALUE : now + ttl.toNanos();
        Segment<V> segment = segmentOf(key);
        List<CacheKey> evicted = List.of();
        segment.lock.lock();
        try {
            segment.map.put(key, new TimedEntry<>(value, expireAt));
            evicted = trimSegment(segment);
            counters.increment(CacheCounters.Counter.PUT);
        } finally {
            segment.lock.unlock();
            fireRemoved(evicted);
        }
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        long now = nanoClock.getAsLong();
        long expireAt = ttl == null ? Long.MAX_VALUE : now + ttl.toNanos();
        Segment<V> segment = segmentOf(key);
        List<CacheKey> evicted = List.of();
        segment.lock.lock();
        try {
            TimedEntry<V> existing = segment.map.get(key);
            if (existing != null && !existing.expired(now)) {
                return false;
            }
            segment.map.put(key, new TimedEntry<>(value, expireAt));
            evicted = trimSegment(segment);
            counters.increment(CacheCounters.Counter.PUT);
            return true;
        } finally {
            segment.lock.unlock();
            fireRemoved(evicted);
        }
    }

    @Override
    public void evict(CacheKey key) {
        Segment<V> segment = segmentOf(key);
        segment.lock.lock();
        try {
            segment.map.remove(key);
        } finally {
            segment.lock.unlock();
            fireRemoved(key);
        }
    }

    @Override
    public void clear() {
        for (Segment<V> segment : segments) {
            segment.lock.lock();
            try {
                segment.map.clear();
            } finally {
                segment.lock.unlock();
            }
        }
    }

    @Override
    public Set<CacheKey> keys() {
        Set<CacheKey> result = new HashSet<>();
        long now = nanoClock.getAsLong();
        for (Segment<V> segment : segments) {
            segment.lock.lock();
            try {
                segment.map.forEach((key, entry) -> {
                    if (!entry.expired(now)) {
                        result.add(key);
                    }
                });
            } finally {
                segment.lock.unlock();
            }
        }
        return result;
    }

    @Override
    public boolean access(CacheKey key) {
        // STATIC 不记频；DYNAMIC 由装饰器负责
        return true;
    }

    @Override
    public void maintain() {
        long now = nanoClock.getAsLong();
        for (Segment<V> segment : segments) {
            List<CacheKey> removed = new ArrayList<>();
            segment.lock.lock();
            try {
                var iterator = segment.map.entrySet().iterator();
                while (iterator.hasNext()) {
                    var current = iterator.next();
                    if (current.getValue().expired(now)) {
                        removed.add(current.getKey());
                        iterator.remove();
                    }
                }
            } finally {
                segment.lock.unlock();
            }
            fireRemoved(removed);
        }
    }

    @Override
    public void onEntryRemoved(Consumer<CacheKey> listener) {
        this.removalListener = listener == null ? key -> {
        } : listener;
    }

    /**
     * 段内超出容量时淘汰最旧条目。
     *
     * @param segment 段（调用方必须已持锁）
     * @return 被淘汰的 key
     */
    private List<CacheKey> trimSegment(Segment<V> segment) {
        List<CacheKey> evicted = List.of();
        while (segment.map.size() > perSegmentCapacity) {
            var iterator = segment.map.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            CacheKey eldest = iterator.next().getKey();
            iterator.remove();
            if (evicted.isEmpty()) {
                evicted = new ArrayList<>(1);
            }
            evicted.add(eldest);
        }
        return evicted;
    }

    private void fireRemoved(CacheKey key) {
        removalListener.accept(key);
    }

    private void fireRemoved(List<CacheKey> keys) {
        for (CacheKey key : keys) {
            removalListener.accept(key);
        }
    }

    @Override
    public CacheStats stats() {
        counters.setEstimatedSize(size());
        return counters;
    }

    /**
     * @return 本地近似条目数
     */
    public long size() {
        long total = 0L;
        for (Segment<V> segment : segments) {
            segment.lock.lock();
            try {
                total += segment.map.size();
            } finally {
                segment.lock.unlock();
            }
        }
        return total;
    }

    private Segment<V> segmentOf(CacheKey key) {
        int hash = key.logical().hashCode();
        return segments[(hash ^ (hash >>> 16)) & (SEGMENTS - 1)];
    }

    /**
     * 单段：锁 + 访问序 LinkedHashMap。
     *
     * @param <V> 值类型
     */
    private static final class Segment<V> {
        private final ReentrantLock lock = new ReentrantLock();
        private final LinkedHashMap<CacheKey, TimedEntry<V>> map =
                new LinkedHashMap<>(16, 0.75F, true);
    }

    /**
     * 带过期时刻的条目。
     *
     * @param entry        本地副本
     * @param expireAtNanos 过期时刻（{@link Long#MAX_VALUE} 表示不过期）
     * @param <V>          值类型
     */
    private record TimedEntry<V>(LocalEntry<V> entry, long expireAtNanos) {

        boolean expired(long now) {
            return expireAtNanos != Long.MAX_VALUE && now - expireAtNanos >= 0L;
        }
    }
}
