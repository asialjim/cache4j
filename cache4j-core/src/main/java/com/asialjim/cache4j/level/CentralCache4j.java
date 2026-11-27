package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4j;
import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.StrategyCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

/**
 * 中央缓存
 *
 * @param <V>
 */
public interface CentralCache4j<V> extends StrategyCache4j<Cache4jKey, V> {
    /**
     * 动态本地缓存生产商
     */
    @FunctionalInterface
    interface Provider {
        <V> CentralCache4j<V> provide(CacheStrategy strategy);
    }

}