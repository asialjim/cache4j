package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.local.NoneLocalCache;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.CacheStrategy;

import com.asialjim.cache4j.core.CacheKey;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 本地缓存工厂：按策略装配默认（零依赖 HashMap）实现。
 *
 * @author Asial Jim
 */
public final class LocalCaches {

    private LocalCaches() {
    }

    /**
     * 按配置创建本地缓存（不含热 key 协调器，等价于 {@code coordination=LOCAL}）。
     *
     * @param strategy 配置
     * @param <V>  值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(CacheStrategy strategy) {
        return create(strategy, null, System::nanoTime, null, false);
    }

    /**
     * 按配置创建本地缓存。
     *
     * @param strategy        配置
     * @param coordinator 热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param <V>         值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator) {
        return create(strategy, coordinator, System::nanoTime, null, false);
    }

    /**
     * 按配置创建本地缓存，并注入单调时钟（测试用）。
     *
     * @param strategy        配置
     * @param coordinator 热 key 协调器
     * @param nanoClock   单调时钟
     * @param <V>         值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                                          LongSupplier nanoClock) {
        return create(strategy, coordinator, nanoClock, null, false);
    }

    /**
     * 按配置创建本地缓存，并注入中央版本读取器（对账用）。
     *
     * @param strategy          配置
     * @param coordinator   热 key 协调器
     * @param nanoClock     单调时钟
     * @param versionReader 中央版本读取器；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     * @param <V>           值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(CacheStrategy strategy, HotKeyCoordinator coordinator,
                                          LongSupplier nanoClock,
                                          Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader, boolean failOpen) {
        return switch (strategy.localStrategy()) {
            case NONE -> new NoneLocalCache<>(strategy);
            case STATIC -> new HashMapLocalCache<>(strategy, nanoClock, versionReader, failOpen);
            case DYNAMIC -> {
                if (strategy.coordination() == com.asialjim.cache4j.core.Coordination.GLOBAL
                        && coordinator == null) {
                    throw new com.asialjim.cache4j.core.CacheException(
                            com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC,
                            "coordination=GLOBAL 必须提供 HotKeyCoordinator，不静默退化为单节点模式");
                }
                yield new HashMapDynamicLocalCache<>(strategy, coordinator, nanoClock, versionReader, failOpen);
            }
        };
    }
}
