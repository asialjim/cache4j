package com.asialjim.cache4j.level;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.StrategyCache4j;

/**
 * 本地缓存
 * @param <V>
 */
public interface LocalCache4j<V> extends StrategyCache4j<Cache4jKey, V> {

}