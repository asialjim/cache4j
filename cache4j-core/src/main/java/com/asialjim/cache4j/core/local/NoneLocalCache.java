package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;

import java.time.Duration;

/**
 * {@code NONE} 策略的本地缓存：不存任何东西。
 *
 * <p>语义：读恒未命中（于是上层自然去查中央）、写恒无操作。它<strong>不在</strong>
 * {@link VersionedLocalCache} 这条分支上——没有副本，就没有版本，也就没有对账。</p>
 *
 * <p><strong>做成 {@code record} 而不是接口 + 各模块实现</strong>：NONE 与存储无关，
 * 换存储不会改变它的行为，让每个存储模块各写一份 NoOp 没有意义。</p>
 *
 * @param spec 配置（保留是为了让上层能读到策略与 namespace）
 * @param <V>  值类型
 * @author Asial Jim
 */
public record NoneLocalCache<V>(LocalCacheSpec spec) implements LocalCache<V> {

    @Override
    public LocalStrategy strategy() {
        return LocalStrategy.NONE;
    }

    @Override
    public Lookup<V> lookup(CacheKey key) {
        return Lookup.miss();
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return false;
    }

    @Override
    public void put(CacheKey key, V value, Duration ttl) {
        // 不存本地
    }

    @Override
    public boolean putIfAbsent(CacheKey key, V value, Duration ttl) {
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
    public void maintain() {
        // 无本地状态
    }
}
