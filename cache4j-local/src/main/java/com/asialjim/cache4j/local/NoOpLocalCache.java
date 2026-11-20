package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.Set;

/**
 * {@code NONE} 策略：本地不存值。
 *
 * <p>语义：{@link #available()} 返回 {@code false}；{@code lookup} 永远 {@code Miss}；
 * {@code put} / {@code evict} / {@code clear} 都是空操作；不参与版本对账（没有本地副本）。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class NoOpLocalCache<V> implements LocalCache<V> {

    private final LocalCacheSpec spec;

    /**
     * @param spec 配置（策略必须是 {@code NONE}）
     */
    public NoOpLocalCache(LocalCacheSpec spec) {
        this.spec = spec;
    }

    @Override
    public LocalStrategy strategy() {
        return LocalStrategy.NONE;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean putIfNewer(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return false;
    }

    @Override
    public LocalEntry<V> peek(CacheKey key) {
        return null;
    }

    @Override
    public Set<CacheKey> keys() {
        return Set.of();
    }

    @Override
    public boolean access(CacheKey key) {
        return false;
    }

    @Override
    public void maintain() {
        // 无本地状态
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        return Lookup.miss();
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return false;
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        // 不存本地
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return false;
    }

    @Override
    public void evict(CacheKey key) {
        // 无本地状态
    }

    @Override
    public void clear() {
        // 无本地状态
    }

    @Override
    public CacheStats stats() {
        return CacheStats.noop();
    }
}
