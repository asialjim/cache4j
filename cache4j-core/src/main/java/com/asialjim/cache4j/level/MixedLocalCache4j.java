package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Cache4jKeyVersion;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 混合本地缓存
 * 同时支持三种本地缓存策略
 * NONE/STATIC/DYNAMIC
 * 之所以如此设计，是为了适配缓存策略动态修改的情况
 * 即：
 * 缓存策略中的本地缓存策略，可能因为运营者修改本地缓存策略而产生变更，
 * 而产生变更后，本混合本地缓存策略也就路由到具体的本地策略的缓存里面去
 *
 * @param <V>
 */
public final class MixedLocalCache4j<V> implements LocalCache4j<V>, Cache4jKeyVersionDetector {
    private final CacheStrategy cacheStrategy;
    private final NoneLocalCache4j<V> noneLocalCache4j;
    private final StaticLocalCache4j<V> staticLocalCache4j;
    private final DynamicLocalCache4j<V> dynamicLocalCache4j;

    public MixedLocalCache4j(
            CacheStrategy strategy,
            StaticLocalCache4j.Provider staticLocalCacheProvider,
            DynamicLocalCache4j.Provider dynamicLocalCacheProvider
    ) {
        this.cacheStrategy = strategy;
        //noinspection unchecked
        this.noneLocalCache4j = (NoneLocalCache4j<V>) NoneLocalCache4j.INSTANCE;
        this.staticLocalCache4j = staticLocalCacheProvider.provide(strategy);
        this.dynamicLocalCache4j = dynamicLocalCacheProvider.provide(strategy);
    }

    @Override
    public Cache4jKeyVersion keyVersion(String key, CacheStrategy strategy) {
        return switch (strategy.localStrategy().get()) {
            case NONE -> Cache4jKeyVersion.NULL_VERSION_KEY;
            case STATIC -> this.staticLocalCache4j.keyVersion(key, strategy);
            case DYNAMIC -> this.dynamicLocalCache4j.keyVersion(key, strategy);
        };
    }

    @Override
    public Map<String, Long> partitionVersion(Set<String> key, CacheStrategy strategy) {
        return switch (strategy.localStrategy().get()) {
            case NONE -> new HashMap<>();
            case STATIC -> this.staticLocalCache4j.partitionVersion(key, strategy);
            case DYNAMIC -> this.dynamicLocalCache4j.partitionVersion(key, strategy);
        };
    }

    @Override
    public CacheStrategy getCacheStrategy() {
        return this.cacheStrategy;
    }

    @Override
    public V get(Cache4jKey key, Supplier<V> valueLoader, CacheStrategy strategySnapshot) {
        return strategyLocalCache(strategySnapshot).get(key, valueLoader, strategySnapshot);
    }

    @Override
    public Lookup<V> lookup(Cache4jKey key, CacheStrategy strategySnapshot) {
        Lookup<V> lookup = strategyLocalCache(strategySnapshot).lookup(key, strategySnapshot);
        if (lookup.isNegative()) {
            if (!CacheStrategy.nullable(strategySnapshot))
                return Lookup.miss();
        }
        return lookup;
    }

    @Override
    public void put(Cache4jKey key, V value, CacheStrategy strategySnapshot) {
        boolean put = Objects.nonNull(value) || CacheStrategy.nullable(strategySnapshot);

        if (put)
            strategyLocalCache(strategySnapshot).put(key, value, strategySnapshot);
    }

    @Override
    public void putSync(Cache4jKey key, V value, CacheStrategy strategySnapshot) {
        boolean put = Objects.nonNull(value) || CacheStrategy.nullable(strategySnapshot);
        if (put)
            strategyLocalCache(strategySnapshot).putSync(key, value, strategySnapshot);
    }

    @Override
    public void evict(Cache4jKey key, CacheStrategy strategySnapshot) {
        this.noneLocalCache4j.evict(key, strategySnapshot);
        this.staticLocalCache4j.evict(key, strategySnapshot);
        this.dynamicLocalCache4j.evict(key, strategySnapshot);
    }

    @Override
    public void clear(CacheStrategy strategySnapshot) {
        this.noneLocalCache4j.clear(strategySnapshot);
        this.staticLocalCache4j.clear(strategySnapshot);
        this.dynamicLocalCache4j.clear(strategySnapshot);
    }


    private LocalCache4j<V> strategyLocalCache(CacheStrategy cacheStrategy) {
        return switch (cacheStrategy.localStrategy().get()) {
            case NONE -> this.noneLocalCache4j;
            case STATIC -> this.staticLocalCache4j;
            case DYNAMIC -> this.dynamicLocalCache4j;
        };
    }
}