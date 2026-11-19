package com.asialjim.cache4j.central;

import com.asialjim.cache4j.core.CacheCodec;
import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.VersionedLookup;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * 内存中央缓存：<strong>仅供测试与单机</strong>，不跨进程。
 *
 * <p>语义与 Redis 实现保持一致：每 key 一个版本（时间戳，写时取 {@code max(now, cur + 1)}），
 * 写值与版本在同一临界区内完成；{@link #evict} 连同版本一起删除，因此删除对其他节点可观
 * （对账时"中央已无此 key"）。</p>
 *
 * <p>内存实现不做序列化：{@code codec} 只为与 {@code CentralCacheProvider} 的签名一致而接收。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class InMemoryCentralCache<V> implements CentralCache<V> {

    private final CentralCacheSpec spec;
    private final CacheCodec<V> codec;
    private final ConcurrentHashMap<CacheKey, Entry<V>> data = new ConcurrentHashMap<>();
    private final CacheCounters counters = new CacheCounters();
    private final LongSupplier milliClock;

    /**
     * @param spec       配置
     * @param codec      值编解码器（内存实现不序列化）
     * @param milliClock 毫秒时钟
     */
    public InMemoryCentralCache(CentralCacheSpec spec, CacheCodec<V> codec, LongSupplier milliClock) {
        this.spec = spec;
        this.codec = codec;
        this.milliClock = milliClock;
    }

    @Override
    public String name() {
        return spec.namespace();
    }

    @Override
    public Lookup<V> lookup(CacheKey key) {
        return lookupWithRevision(key).lookup();
    }

    @Override
    public Map<CacheKey, Lookup<V>> lookupAll(Collection<CacheKey> keys) {
        Map<CacheKey, Lookup<V>> result = new HashMap<>(keys.size() * 2);
        keys.forEach(key -> result.put(key, lookup(key)));
        return result;
    }

    @Override
    public VersionedLookup<V> lookupWithRevision(CacheKey key) {
        counters.increment(CacheCounters.Counter.COMMAND);
        Entry<V> entry = liveEntry(key);
        if (entry == null) {
            counters.increment(CacheCounters.Counter.STORE_MISS);
            return new VersionedLookup<>(0L, Lookup.miss());
        }
        if (entry.negative) {
            counters.increment(CacheCounters.Counter.STORE_HIT);
            return new VersionedLookup<>(entry.revision, Lookup.negative());
        }
        counters.increment(CacheCounters.Counter.STORE_HIT);
        return new VersionedLookup<>(entry.revision, Lookup.hit(entry.value));
    }

    @Override
    public Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys) {
        Map<CacheKey, VersionedLookup<V>> result = new HashMap<>(keys.size() * 2);
        keys.forEach(key -> result.put(key, lookupWithRevision(key)));
        return result;
    }

    @Override
    public long revision(CacheKey key) {
        Entry<V> entry = liveEntry(key);
        return entry == null ? 0L : entry.revision;
    }

    @Override
    public Map<CacheKey, Long> revisions(Collection<CacheKey> keys) {
        counters.increment(CacheCounters.Counter.COMMAND);
        Map<CacheKey, Long> result = new HashMap<>(keys.size() * 2);
        for (CacheKey key : keys) {
            Entry<V> entry = liveEntry(key);
            if (entry != null) {
                result.put(key, entry.revision);
            }
        }
        return result;
    }

    @Override
    public long putAndGetRevision(CacheKey key, V value, Duration ttl) {
        return store(key, value, false, effectiveTtl(ttl != null ? ttl : spec.ttl()));
    }

    @Override
    public long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
        Duration effective = ttl != null ? ttl : spec.nullTtl();
        return store(key, null, true, effectiveTtl(effective));
    }

    @Override
    public OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl) {
        counters.increment(CacheCounters.Counter.COMMAND);
        long now = milliClock.getAsLong();
        long newRevision;
        Entry<V> created;
        synchronized (data) {
            Entry<V> existing = data.get(key);
            if (existing != null && !existing.expired(now)) {
                return OptionalLong.empty();
            }
            newRevision = nextRevision(existing, now);
            created = new Entry<>(value, false, newRevision, expireAt(effectiveTtl(
                    ttl != null ? ttl : spec.ttl()), now));
            data.put(key, created);
        }
        return OptionalLong.of(newRevision);
    }

    @Override
    public void put(CacheKey key, V value, Duration ttl) {
        putAndGetRevision(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, V value, Duration ttl) {
        return putIfAbsentAndGetRevision(key, value, ttl).isPresent();
    }

    @Override
    public void evict(CacheKey key) {
        counters.increment(CacheCounters.Counter.COMMAND);
        data.remove(key);
    }

    @Override
    public void clear() {
        counters.increment(CacheCounters.Counter.COMMAND);
        data.clear();
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return liveEntry(key) != null;
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public CacheStats stats() {
        counters.setEstimatedSize(-1L);
        return counters;
    }

    @Override
    public CentralCacheSpec spec() {
        return spec;
    }

    /**
     * @return 当前条目数（测试用）
     */
    public int size() {
        return data.size();
    }

    /**
     * @return 接收到的编解码器（内存实现不序列化）
     */
    public CacheCodec<V> codec() {
        return codec;
    }

    private long store(CacheKey key, V value, boolean negative, Duration ttl) {
        counters.increment(CacheCounters.Counter.COMMAND);
        long now = milliClock.getAsLong();
        synchronized (data) {
            Entry<V> existing = data.get(key);
            long revision = nextRevision(existing, now);
            data.put(key, new Entry<>(value, negative, revision, expireAt(ttl, now)));
            return revision;
        }
    }

    private long nextRevision(Entry<V> existing, long now) {
        long previous = existing == null || existing.expired(now) ? 0L : existing.revision;
        return Math.max(now, previous + 1L);
    }

    private Entry<V> liveEntry(CacheKey key) {
        Entry<V> entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(milliClock.getAsLong())) {
            data.remove(key, entry);
            return null;
        }
        return entry;
    }

    private Duration effectiveTtl(Duration ttl) {
        if (ttl == null || ttl.isZero()) {
            return ttl;
        }
        double jitter = spec.ttlJitter();
        if (jitter <= 0D) {
            return ttl;
        }
        double factor = 1D + (ThreadLocalRandom.current().nextDouble() * 2D - 1D) * jitter;
        return Duration.ofMillis(Math.max(1L, (long) (ttl.toMillis() * factor)));
    }

    private long expireAt(Duration ttl, long now) {
        if (ttl == null || ttl.isZero()) {
            return Long.MAX_VALUE;
        }
        return now + ttl.toMillis();
    }

    /**
     * 内存条目。
     *
     * @param value      值；负副本为 {@code null}
     * @param negative   是否"已确认不存在"
     * @param revision   版本
     * @param expireAtMillis 过期时刻（{@link Long#MAX_VALUE} 表示不过期）
     * @param <V>        值类型
     */
    private record Entry<V>(V value, boolean negative, long revision, long expireAtMillis) {

        boolean expired(long now) {
            return expireAtMillis != Long.MAX_VALUE && now - expireAtMillis >= 0L;
        }
    }
}
