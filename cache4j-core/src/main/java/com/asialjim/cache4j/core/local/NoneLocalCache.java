package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;

import java.time.Duration;
import java.util.Set;

/**
 * {@code NONE} 策略的本地缓存。
 *
 * <p>语义：本地永不存值，读请求直接落到中央缓存；中央命中的值也不回填本地。</p>
 *
 * <p><strong>做成 {@code record} 而不是接口 + 各模块实现</strong>：NONE 与存储无关——
 * 它不存任何东西，"实现"就是把写变成无操作、把读恒定为未命中。
 * 让每个存储模块各写一份 NoOp 实现没有意义（换存储也不会改变 NONE 的行为）。</p>
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
    public boolean available() {
        return false;
    }

    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        return Lookup.miss();
    }

    @Override
    public LocalEntry<V> peek(CacheKey key) {
        return null;
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        // 不存本地
    }

    @Override
    public boolean putIfNewer(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return false;
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return false;
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return false;
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
