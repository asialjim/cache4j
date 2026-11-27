package com.asialjim.cache4j;

import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.function.Supplier;

public interface StrategyCache4j<K, V> extends Cache4j<K, V> {
    /**
     * 缓存策略
     */
    CacheStrategy getCacheStrategy();

    @Override
    default String name() {
        return getCacheStrategy().namespace();
    }

    default V get(K key, Supplier<V> valueLoader) {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        return get(key, valueLoader, snapshot);
    }

    V get(K key, Supplier<V> valueLoader, CacheStrategy strategySnapshot);

    /**
     * 读取缓存，不回源数据源。
     *
     * @param key key
     * @return 三态结果
     */
    default Lookup<V> lookup(K key) {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        return lookup(key, snapshot);
    }

    Lookup<V> lookup(K key, CacheStrategy strategySnapshot);

    /**
     * 写入。
     *
     * @param key   key
     * @param value 值；{@code null} 表示"确认不存在"
     */
    default void put(K key, V value) {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        put(key, value, snapshot);
    }

    default void putSync(K key, V value) {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        putSync(key,value,snapshot);
    }

    void put(K key, V value, CacheStrategy strategySnapshot);

    void putSync(K key, V value, CacheStrategy strategySnapshot);

    /**
     * 失效单个 key。
     *
     * @param key key
     */
    default void evict(K key) {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        evict(key, snapshot);
    }

    void evict(K key, CacheStrategy strategySnapshot);

    /**
     * 清空该缓存名下的全部数据。
     */
    default void clear() {
        CacheStrategy snapshot = getCacheStrategy().snapshot();
        clear(snapshot);
    }

    void clear(CacheStrategy strategySnapshot);
}
