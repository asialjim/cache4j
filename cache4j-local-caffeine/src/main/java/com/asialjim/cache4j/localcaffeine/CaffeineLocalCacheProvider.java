package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.local.LocalCacheProvider;

/**
 * Caffeine 本地缓存 provider：{@code cache4j-local} 的 {@link LocalCacheProvider} 的第二套存储实现。
 *
 * @author Asial Jim
 */
public final class CaffeineLocalCacheProvider implements LocalCacheProvider {

    /**
     * 单例。
     */
    public static final CaffeineLocalCacheProvider INSTANCE = new CaffeineLocalCacheProvider();

    public <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                                    java.util.function.Function<java.util.Collection<com.asialjim.cache4j.core.CacheKey>,
                                            java.util.Map<com.asialjim.cache4j.core.CacheKey, Long>> versionReader,
                                    boolean failOpen) {
        return CaffeineLocalCaches.create(strategy, coordinator, System::nanoTime, versionReader, failOpen);
    }
}
