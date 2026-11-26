package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.CacheStrategy;

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
     * @param strategy        配置
     * @param coordinator 热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param <V>         值类型
     * @return 本地缓存
     */
    default <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator) {
        return create(strategy, coordinator, null, false);
    }

    /**
     * 创建本地缓存，并注入中央版本读取器（对账用）。
     *
     * @param strategy          配置
     * @param coordinator   热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param versionReader 中央版本读取器；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     * @param <V>           值类型
     * @return 本地缓存
     */
    default <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                                     java.util.function.Function<java.util.Collection<com.asialjim.cache4j.core.CacheKey>,
                                             java.util.Map<com.asialjim.cache4j.core.CacheKey, Long>> versionReader,
                                     boolean failOpen) {
        return create(strategy, coordinator, versionReader, failOpen, System::nanoTime);
    }

    /**
     * 创建本地缓存，并注入单调时钟（测试用）。
     *
     * @param strategy      配置
     * @param coordinator   热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param versionReader 中央版本读取器；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     * @param nanoClock     单调时钟
     * @param <V>           值类型
     * @return 本地缓存
     */
    <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                             java.util.function.Function<java.util.Collection<com.asialjim.cache4j.core.CacheKey>,
                                     java.util.Map<com.asialjim.cache4j.core.CacheKey, Long>> versionReader,
                             boolean failOpen, java.util.function.LongSupplier nanoClock);

    /**
     * 创建本地缓存（单机协调）。
     *
     * @param strategy 配置
     * @param <V>  值类型
     * @return 本地缓存
     */
    default <V> LocalCache<V> create(CacheStrategy strategy) {
        return create(strategy, null);
    }
}
