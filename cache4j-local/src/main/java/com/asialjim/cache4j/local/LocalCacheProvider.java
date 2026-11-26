package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

/**
 * 本地缓存 provider：把配置装配成 {@link LocalCache}。
 *
 * <p>{@code cache4j-local} 同时提供默认实现与装配入口，便于后续 {@code cache4j-local-caffeine}
 * 以同一接口替换存储层。</p>
 *
 * @author Asial Jim
 */
public interface LocalCacheProvider {

    /**
     * 创建本地缓存。
     *
     * @param spec        配置
     * @param coordinator 热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param <V>         值类型
     * @return 本地缓存
     */
    default <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator) {
        return create(spec, coordinator, null, false);
    }

    /**
     * 创建本地缓存，并注入中央版本读取器（对账用）。
     *
     * @param spec          配置
     * @param coordinator   热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param versionReader 中央版本读取器；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     * @param <V>           值类型
     * @return 本地缓存
     */
    <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator,
                             java.util.function.Function<java.util.Collection<com.asialjim.cache4j.core.CacheKey>,
                                     java.util.Map<com.asialjim.cache4j.core.CacheKey, Long>> versionReader,
                             boolean failOpen);

    /**
     * 创建本地缓存（单机协调）。
     *
     * @param spec 配置
     * @param <V>  值类型
     * @return 本地缓存
     */
    default <V> LocalCache<V> create(LocalCacheSpec spec) {
        return create(spec, null);
    }
}
