package com.asialjim.cache4j.spring;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Spring {@link CacheManager} 到 {@link TwoLevelCacheManager} 的桥接。
 *
 * @author Asial Jim
 */
public final class SpringCacheManagerAdapter implements CacheManager {

    private final TwoLevelCacheManager delegate;

    /**
     * @param delegate 二级缓存管理器
     */
    public SpringCacheManagerAdapter(TwoLevelCacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        return delegate.cache(name).map(SpringCacheAdapter::new).orElse(null);
    }

    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(delegate.names());
        return names;
    }

    /**
     * @return 底层管理器
     */
    public TwoLevelCacheManager unwrap() {
        return delegate;
    }
}
