package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.strategy.CacheStrategy;

/**
 * 静态本地缓存
 *
 * @param <V>
 */
public interface StaticLocalCache4j<V> extends LocalCache4j<V>, Cache4jKeyVersionDetector {

    /**
     * 静态本地缓存供应商
     */
    @FunctionalInterface
    interface Provider {
        <V> StaticLocalCache4j<V> provide(CacheStrategy strategy);
    }
}