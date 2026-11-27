package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.strategy.CacheStrategy;
import com.asialjim.cache4j.strategy.CacheStrategyProperty;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 本地缓存策略
 * NONE，本地不缓存
 *
 * @param <V>
 */
public record NoneLocalCache4j<V>() implements LocalCache4j<V> {
    public static final NoneLocalCache4j<?> INSTANCE = new NoneLocalCache4j<>();

    @Override
    public CacheStrategy getCacheStrategy() {
        return CacheStrategyProperty.noneLocalCacheStrategy;
    }

    @Override
    public V get(Cache4jKey key, Supplier<V> valueLoader, CacheStrategy strategySnapshot) {
        return Objects.nonNull(valueLoader) ? valueLoader.get() : null;
    }

    @Override
    public Lookup<V> lookup(Cache4jKey key, CacheStrategy strategySnapshot) {
        return Lookup.miss();
    }

    @Override
    public void put(Cache4jKey key, V value, CacheStrategy strategySnapshot) {

    }

    @Override
    public void putSync(Cache4jKey key, V value, CacheStrategy strategySnapshot) {

    }


    @Override
    public void evict(Cache4jKey key, CacheStrategy strategySnapshot) {

    }

    @Override
    public void clear(CacheStrategy strategySnapshot) {

    }
}