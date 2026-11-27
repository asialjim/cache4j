package com.asialjim.cache4j.strategy;

import com.asialjim.cache4j.Cache4j;
import com.asialjim.cache4j.key.StringKeyEncoder;
import com.asialjim.cache4j.level.Cache4jImpl;
import com.asialjim.cache4j.level.CentralCache4j;
import com.asialjim.cache4j.level.DynamicLocalCache4j;
import com.asialjim.cache4j.level.StaticLocalCache4j;

/**
 * 缓存策略读取器
 */
public class CacheStrategyReader {
    /**
     * 数据源
     */
    private final CacheStrategyDataSource dataSource;
    /**
     * 缓存策略自举缓存
     */
    private final Cache4j<String, CacheStrategy> cacheStrategyCache;

    public CacheStrategyReader(CacheStrategyDataSource dataSource,
                               StaticLocalCache4j.Provider staticLocalCacheProvider,
                               DynamicLocalCache4j.Provider dynamicLocalCacheProvider,
                               CentralCache4j.Provider centralCacheProvider) {
        this.dataSource = dataSource;

        this.cacheStrategyCache = new Cache4jImpl<>(
                CacheStrategyProperty.selfCacheStrategy,
                StringKeyEncoder.INSTANCE,
                staticLocalCacheProvider,
                dynamicLocalCacheProvider,
                centralCacheProvider
        );
    }

    public CacheStrategy namespaceOf(String namespace) {
        return this.cacheStrategyCache.get(namespace, () -> this.dataSource.namespaceOf(namespace));
    }


    /**
     * 必要情况下，注册缓存侧录到中央缓存
     */
    public void registerIfAbsent(CacheStrategyProperty property) {
        this.dataSource.registerIfAbsent(property);
    }


}