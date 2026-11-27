package com.asialjim.cache4j.strategy;

/**
 * 缓存策略数据源
 */
public interface CacheStrategyDataSource {

    /**
     * 根据命名空间，查询缓存策略
     */
    CacheStrategy namespaceOf(String namespace);

    /**
     * 必要情况下，注册缓存侧录到中央缓存
     */
    void registerIfAbsent(CacheStrategyProperty property);

    //...   其他方法
}
