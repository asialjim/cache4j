package com.asialjim.cache4j.level;

import com.asialjim.cache4j.*;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 缓存门面，编排本地缓存和中央缓存
 *
 * @param <K>
 * @param <V>
 */
public final class Cache4jImpl<K, V> implements StrategyCache4j<K, V> {
    private final CacheStrategy cacheStrategy;
    private final KeyEncoder keyEncoder;
    private final MixedLocalCache4j<V> localCache4j;
    private final CentralCache4j<V> centralCache4j;

    public Cache4jImpl(CacheStrategy cacheStrategy,
                       KeyEncoder keyEncoder,
                       StaticLocalCache4j.Provider staticLocalCacheProvider,
                       DynamicLocalCache4j.Provider dynamicLocalCacheProvider,
                       CentralCache4j.Provider centralCache4jProvider) {
        this.cacheStrategy = cacheStrategy;
        this.keyEncoder = keyEncoder;
        this.localCache4j = new MixedLocalCache4j<>(cacheStrategy, staticLocalCacheProvider, dynamicLocalCacheProvider);
        this.centralCache4j = centralCache4jProvider.provide(cacheStrategy);
    }

    @Override
    public CacheStrategy getCacheStrategy() {
        return this.cacheStrategy;
    }

    @Override
    public V get(K key, Supplier<V> valueLoader, CacheStrategy strategySnapshot) {
        Cache4jKey cacheKey = this.key(key, strategySnapshot);
        return this.localCache4j.get(
                cacheKey,
                () -> this.centralCache4j.get(
                        cacheKey,
                        valueLoader,
                        strategySnapshot
                ),
                strategySnapshot);
    }

    @Override
    public Lookup<V> lookup(K key, CacheStrategy strategySnapshot) {
        Cache4jKey cacheKey = this.key(key, strategySnapshot);
        // 本地副本
        Lookup<V> localLookUp = this.localCache4j.lookup(cacheKey, strategySnapshot);

        // 本地存在缓存,直接返回
        // 如果缓存策略不允许空值缓存，但确实本地查询到的缓存为空时，应当相应Lookup.Miss
        if (!localLookUp.isMiss())
            return localLookUp;

        // 中央缓存副本
        Lookup<V> centralLookup = this.centralCache4j.lookup(cacheKey, strategySnapshot);
        // 中央缓存命中
        if (!centralLookup.isMiss()) {
            V value = centralLookup.optional().orElse(null);
            // 如果缓存策略明确不允许缓存空值，则不应存储数据
            this.localCache4j.put(cacheKey, value, strategySnapshot);
        }

        return centralLookup;
    }

    @Override
    public void put(K key, V value, CacheStrategy strategySnapshot) {
        Cache4jKey cacheKey = this.key(key, strategySnapshot);
        centralCache4j.put(cacheKey, value, strategySnapshot);
        this.evictLocal(cacheKey,strategySnapshot);
    }

    @Override
    public void putSync(K key, V value, CacheStrategy strategySnapshot) {
        Cache4jKey cacheKey = this.key(key, strategySnapshot);
        centralCache4j.putSync(cacheKey, value, strategySnapshot);
        this.evictLocal(cacheKey, strategySnapshot);
    }


    @Override
    public void evict(K key, CacheStrategy strategySnapshot) {
        Cache4jKey cacheKey = this.key(key, strategySnapshot);
        this.centralCache4j.evict(cacheKey, strategySnapshot);
        this.localCache4j.evict(cacheKey, strategySnapshot);
    }

    @Override
    public void clear(CacheStrategy strategySnapshot) {
        this.centralCache4j.clear(strategySnapshot);
        this.localCache4j.clear(strategySnapshot);
    }

    private void evictLocal(Cache4jKey key, CacheStrategy strategySnapshot) {
        this.localCache4j.evict(key, strategySnapshot);
    }

    private Cache4jKey key(K key, CacheStrategy cacheStrategySnapshot) {
        String keyString = keyEncoder.encode(key);
        return new Cache4jKey(cacheStrategySnapshot.namespace(), keyString, localCache4j.keyVersion(keyString, cacheStrategySnapshot));
    }
}
