package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.StaticLocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.VersionedLocalCache;
import com.asialjim.cache4j.core.twolevel.Reconciler;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.function.Function;
import java.util.Collection;
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
/**
 * {@code STATIC} 策略的本地缓存——<strong>HashMap 分段 LRU 存储</strong>。
 *
 * <p>非 {@code final}：{@link HashMapDynamicLocalCache} 继承它复用存储与 key 版本管理，
 * 只覆写准入判定（{@code accepts} / {@code access} / {@code hotSync}）。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public class HashMapLocalCache<V> implements StaticLocalCache<V>, VersionedLocalCache<V> {

    private static final int SEGMENTS = 16;

    private final LocalCacheSpec spec;
    private final Segment<V>[] segments;
    private final int perSegmentCapacity;
    private final LongSupplier nanoClock;

    /**
     * 版本对账：<strong>本地副本的新鲜度是本地自己的事</strong>，门面不需要知道。
     *
     * <p>它只依赖一个"给我这批 key 的权威版本"的函数，因此本地层不依赖任何中央实现。</p>
     */
    private final Reconciler<V> reconciler;

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
    public HashMapLocalCache(LocalCacheSpec spec, LongSupplier nanoClock,
                             Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader, boolean failOpen) {
        this.spec = spec;
        this.nanoClock = nanoClock;
        this.perSegmentCapacity = (int) Math.max(1L, (spec.maxSize() + SEGMENTS - 1) / SEGMENTS);
        this.segments = new Segment[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            segments[i] = new Segment<>();
        }
        this.reconciler = new Reconciler<>(spec.namespace(), this, versionReader, spec.version(), failOpen);
        // 条目过期 / 淘汰 / 失效时立即清掉分区号与分区索引，不等下一次对账才发现
        onEntryRemoved(reconciler::forget);
    }

    @Override
    public LocalStrategy strategy() {
        return spec.strategy();
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public LocalEntry<V> entryOf(CacheKey key) {
        Segment<V> segment = segmentOf(key);
        long now = nanoClock.getAsLong();
        boolean expired = false;
        segment.lock.lock();
        try {
            TimedEntry<V> entry = segment.map.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expired(now)) {
                segment.map.remove(key);
                expired = true;
                return null;
            }
            return entry.entry;
        } finally {
            segment.lock.unlock();
            if (expired) {
                fireRemoved(key);
            }
        }
    }

    @Override
    public Lookup<V> lookup(CacheKey key) {
        Segment<V> segment = segmentOf(key);
        long now = nanoClock.getAsLong();
        reconciler.maybeReconcile(key, now);        // 对账搭在读路径上，本地自行决定时机
        boolean expired = false;
        segment.lock.lock();
        try {
            TimedEntry<V> entry = segment.map.get(key);
            if (entry == null) {
                return Lookup.miss();
            }
            if (entry.expired(now)) {
                segment.map.remove(key);
                expired = true;
                return Lookup.miss();
            }
            return toLookup(entry.entry);
        } finally {
            segment.lock.unlock();
            if (expired) {
                fireRemoved(key);
            }
        }
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return !lookup(key).isMiss();       // 有值或负副本都算"本地有这一条"
    }

    @Override
    public void put(CacheKey key, V value, Duration ttl) {
        long now = nanoClock.getAsLong();
        long expireAt = ttl == null ? Long.MAX_VALUE : now + ttl.toNanos();
        Segment<V> segment = segmentOf(key);
        List<CacheKey> evicted = List.of();
        segment.lock.lock();
        try {
            segment.map.put(key, new TimedEntry<>(entryOf(value, 0L), expireAt));
            reconciler.track(key);        // 登记对账分区（首次访问即算分区号）
            evicted = trimSegment(segment);
        } finally {
            segment.lock.unlock();
            fireRemoved(evicted);
        }
    }

    @Override
    public boolean putIfNewer(CacheKey key, V value, long revision, Duration ttl) {
        long now = nanoClock.getAsLong();
        long expireAt = ttl == null ? Long.MAX_VALUE : now + ttl.toNanos();
        Segment<V> segment = segmentOf(key);
        List<CacheKey> evicted = List.of();
        segment.lock.lock();
        try {
            TimedEntry<V> existing = segment.map.get(key);
            if (revision != 0 && existing != null && !existing.expired(now)
                    && existing.entry.revision() > revision) {
                return false;
            }
            segment.map.put(key, new TimedEntry<>(entryOf(value, revision), expireAt));
            reconciler.track(key);        // 登记对账分区（首次访问即算分区号）
            evicted = trimSegment(segment);
            return true;
        } finally {
            segment.lock.unlock();
            fireRemoved(evicted);
        }
    }

    @Override
    public boolean putIfAbsent(CacheKey key, V value, Duration ttl) {
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
            segment.map.put(key, new TimedEntry<>(entryOf(value, 0L), expireAt));
            reconciler.track(key);        // 登记对账分区（首次访问即算分区号）
            evicted = trimSegment(segment);
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
        reconciler.clear();
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
    /**
     * 值 + 版本 → 本地条目。{@code value == null} 表示"确认不存在"（负副本）。
     */
    private static <V> LocalEntry<V> entryOf(V value, long revision) {
        return value == null ? LocalEntry.negative(revision) : LocalEntry.hit(value, revision);
    }

    /**
     * 本地条目 → 读结果。版本在这里被丢掉：调用方只关心"有值 / 确认没有 / 不知道"。
     */
    private static <V> Lookup<V> toLookup(LocalEntry<V> entry) {
        return entry.isNegative() ? Lookup.negative() : Lookup.hit(entry.value());
    }

    private record TimedEntry<V>(LocalEntry<V> entry, long expireAtNanos) {

        boolean expired(long now) {
            return expireAtNanos != Long.MAX_VALUE && now - expireAtNanos >= 0L;
        }
    }
}
