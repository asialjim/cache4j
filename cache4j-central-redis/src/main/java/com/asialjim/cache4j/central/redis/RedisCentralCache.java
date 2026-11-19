package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.VersionedLookup;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 语义层：只负责<strong>语义与策略</strong>，把真正的读写下发给 {@link RedisValueStore}。
 *
 * <p>本层做的事：TTL 抖动、空值缓存开关、负副本表达、统计、故障翻译。<strong>不做</strong>的事：
 * 拼 key、封 envelope、写 Lua、序列化、碰字节 —— 那些全在适配层的 {@code RedisValueStore} 实现里。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class RedisCentralCache<V> implements CentralCache<V> {

    private final CentralCacheSpec spec;
    private final RedisValueStore<V> store;
    private final CacheCounters counters = new CacheCounters();

    /**
     * @param spec  配置
     * @param store 下行存储（由适配层实现）
     */
    public RedisCentralCache(CentralCacheSpec spec, RedisValueStore<V> store) {
        this.spec = spec;
        this.store = store;
    }

    // ------------------------------------------------------------------ 读

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
        return lookupAllWithRevision(keys).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().lookup()));
    }

    @Override
    public VersionedLookup<V> lookupWithRevision(CacheKey key) {
        VersionedLookup<V> result = guarded(() -> store.read(key));
        countRead(result.lookup());
        return result;
    }

    @Override
    public Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys) {
        Map<CacheKey, VersionedLookup<V>> result = guarded(() -> store.readAll(keys));
        result.values().forEach(value -> countRead(value.lookup()));
        return result;
    }

    @Override
    public long revision(CacheKey key) {
        return guarded(() -> store.revision(key));
    }

    @Override
    public Map<CacheKey, Long> revisions(Collection<CacheKey> keys) {
        return guarded(() -> store.revisions(keys));
    }

    // ------------------------------------------------------------------ 写

    @Override
    public long putAndGetRevision(CacheKey key, V value, Duration ttl) {
        if (value == null) {
            return putNegativeAndGetRevision(key, ttl);
        }
        Duration effective = effectiveTtl(ttl != null ? ttl : spec.ttl());
        return guarded(() -> store.write(key, value, effective));
    }

    @Override
    public long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
        if (!spec.nullCachingEnabled()) {
            // 关闭空值缓存：等价于删除（连版本一起删）
            guarded(() -> {
                store.delete(key);
                return 0L;
            });
            return 0L;
        }
        Duration effective = effectiveTtl(ttl != null ? ttl : spec.nullTtl());
        return guarded(() -> store.writeNegative(key, effective));
    }

    @Override
    public OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl) {
        Duration effective = effectiveTtl(ttl != null ? ttl : spec.ttl());
        return guarded(() -> store.writeIfAbsent(key, value, effective));
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
        guarded(() -> {
            store.delete(key);
            return 0L;
        });
    }

    @Override
    public void clear() {
        guarded(() -> {
            store.clear();
            return 0L;
        });
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return !lookup(key).isMiss();
    }

    // ------------------------------------------------------------------ 其它

    @Override
    public boolean ping() {
        try {
            return store.ping();
        } catch (RuntimeException e) {
            return false;
        }
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

    @Override
    public void close() {
        // store 的生命周期由 provider 拥有，视图不关闭它
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 计算带抖动的 TTL。
     *
     * @param ttl 原始 TTL；{@code null} 表示不设过期
     * @return 生效 TTL
     */
    Duration effectiveTtl(Duration ttl) {
        if (ttl == null) {
            return null;
        }
        if (ttl.isNegative()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "TTL 不能为负：" + ttl);
        }
        if (ttl.isZero()) {
            if (!spec.allowNoExpiry()) {
                throw new CacheException(CacheResCode.INVALID_SPEC, "中央缓存不允许无 TTL");
            }
            return null;
        }
        double jitter = spec.ttlJitter();
        if (jitter <= 0D) {
            return ttl;
        }
        double factor = 1D + (ThreadLocalRandom.current().nextDouble() * 2D - 1D) * jitter;
        return Duration.ofMillis(Math.max(1L, (long) (ttl.toMillis() * factor)));
    }

    private void countRead(Lookup<V> lookup) {
        counters.increment(CacheCounters.Counter.COMMAND);
        if (lookup.isNegative()) {
            counters.increment(CacheCounters.Counter.STORE_HIT);
        } else if (lookup.isHit()) {
            counters.increment(CacheCounters.Counter.STORE_HIT);
        } else {
            counters.increment(CacheCounters.Counter.STORE_MISS);
        }
    }

    /**
     * 把适配层的 {@link RedisAccessException} 翻译成 {@link CacheException}。
     *
     * @param e 适配层异常
     * @return 缓存异常
     */
    static CacheException translate(RedisAccessException e) {
        CacheResCode code = e.kind() == RedisAccessException.Kind.TIMEOUT
                ? CacheResCode.CENTRAL_TIMEOUT
                : CacheResCode.CENTRAL_UNAVAILABLE;
        return new CacheException(code, e.getMessage(), e);
    }

    private <T> T guarded(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RedisAccessException e) {
            throw translate(e);
        }
    }
}
