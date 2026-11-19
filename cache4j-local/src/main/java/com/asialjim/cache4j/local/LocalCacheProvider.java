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
    <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator);

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
