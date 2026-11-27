package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.strategy.CacheStrategy;

/**
 * 动态本地缓存
 * @param <V>
 */
public interface DynamicLocalCache4j<V> extends LocalCache4j<V>, Cache4jKeyVersionDetector {

    /**
     * 动态本地缓存生产商
     */
    @FunctionalInterface
    interface Provider {
        <V> DynamicLocalCache4j<V> provide(CacheStrategy strategy);
    }
}
