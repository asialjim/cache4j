package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

/**
 * 默认（零依赖 HashMap）本地缓存 provider。
 *
 * @author Asial Jim
 */
public final class HashMapLocalCacheProvider implements LocalCacheProvider {

    /**
     * 单例。
     */
    public static final HashMapLocalCacheProvider INSTANCE = new HashMapLocalCacheProvider();

    @Override
    public <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator) {
        return LocalCaches.create(spec, coordinator);
    }
}
