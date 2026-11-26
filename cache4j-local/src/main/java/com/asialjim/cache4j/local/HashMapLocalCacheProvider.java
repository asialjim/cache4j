package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.CacheStrategy;

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

    public <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                                    java.util.function.Function<java.util.Collection<com.asialjim.cache4j.core.CacheKey>,
                                            java.util.Map<com.asialjim.cache4j.core.CacheKey, Long>> versionReader,
                                    boolean failOpen) {
        return LocalCaches.create(strategy, coordinator, System::nanoTime, versionReader, failOpen);
    }
}
