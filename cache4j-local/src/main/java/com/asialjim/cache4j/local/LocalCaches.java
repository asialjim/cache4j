package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.local.NoneLocalCache;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

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
     * @param spec 配置
     * @param <V>  值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(LocalCacheSpec spec) {
        return create(spec, null, System::nanoTime);
    }

    /**
     * 按配置创建本地缓存。
     *
     * @param spec        配置
     * @param coordinator 热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
     * @param <V>         值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator) {
        return create(spec, coordinator, System::nanoTime);
    }

    /**
     * 按配置创建本地缓存，并注入单调时钟（测试用）。
     *
     * @param spec        配置
     * @param coordinator 热 key 协调器
     * @param nanoClock   单调时钟
     * @param <V>         值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator,
                                          LongSupplier nanoClock) {
        spec.validate();
        return switch (spec.strategy()) {
            case NONE -> new NoneLocalCache<>(spec);
            case STATIC -> new HashMapLocalCache<>(spec, nanoClock);
            case DYNAMIC -> {
                if (spec.dynamic().coordination() == LocalCacheSpec.Coordination.GLOBAL
                        && coordinator == null) {
                    throw new com.asialjim.cache4j.core.CacheException(
                            com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC,
                            "coordination=GLOBAL 必须提供 HotKeyCoordinator，不静默退化为单节点模式");
                }
                yield new HashMapDynamicLocalCache<>(spec, coordinator, nanoClock);
            }
        };
    }
}
