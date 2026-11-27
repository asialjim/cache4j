package com.asialjim.cache4j;


import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Map;
import java.util.Set;

/**
 * cache key 版本探测器
 */
public interface Cache4jKeyVersionDetector {
    /**
     * 从本地缓存中获取版本号
     */
    Cache4jKeyVersion keyVersion(String key, CacheStrategy strategy);

    /**
     * 批量版本号查询
     * <p>
     * 根据 缓存策略 获取分区版本存储位置
     */
    Map<String, Long> partitionVersion(Set<String> key, CacheStrategy strategy);
}
