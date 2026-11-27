package com.asialjim.cache4j;

import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.function.Supplier;

/**
 * 顶级接口：缓存
 *
 * @param <K>
 * @param <V>
 */
public interface Cache4j<K, V> {
    String name();


    V get(K key, Supplier<V> valueLoader);

    /**
     * 读取缓存，不回源。
     *
     * @param key key
     * @return 三态结果
     */
    Lookup<V> lookup(K key);

    /**
     * 写入。
     *
     * @param key   key
     * @param value 值；{@code null} 表示"确认不存在"
     */
    void put(K key, V value);
    void putSync(K key, V value);

        /**
     * 失效单个 key。
     *
     * @param key key
     */
    void evict(K key);

    /**
     * 清空该缓存名下的全部数据。
     */
    void clear();
}
