package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.local.NoneLocalCache;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import com.asialjim.cache4j.core.CacheKey;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Caffeine 存储的装配入口。
 *
 * <p>策略装配规则与默认实现一致：{@code NONE} 用空实现（不存值），{@code STATIC} 直接用 Caffeine，
 * {@code DYNAMIC} 用 {@link DynamicLocalCache} 装饰 Caffeine（热度门控在装饰器，淘汰在 Caffeine）。</p>
 *
 * @author Asial Jim
 */
public final class CaffeineLocalCaches {

    private CaffeineLocalCaches() {
    }

    /**
     * 按配置创建本地缓存。
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
        return create(spec, coordinator, nanoClock, null, false);
    }

    /**
     * 按配置创建本地缓存，并注入中央版本读取器（对账用）。
     *
     * @param spec          配置
     * @param coordinator   热 key 协调器
     * @param nanoClock     单调时钟
     * @param versionReader 中央版本读取器；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     * @param <V>           值类型
     * @return 本地缓存
     */
    public static <V> LocalCache<V> create(LocalCacheSpec spec, HotKeyCoordinator coordinator,
                                          LongSupplier nanoClock,
                                          Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader, boolean failOpen) {
        spec.validate();
        return switch (spec.strategy()) {
            case NONE -> new com.asialjim.cache4j.core.local.NoneLocalCache<>(spec);
            case STATIC -> new CaffeineLocalCache<>(spec, nanoClock, versionReader, failOpen);
            case DYNAMIC -> {
                if (spec.dynamic().coordination() == LocalCacheSpec.Coordination.GLOBAL
                        && coordinator == null) {
                    throw new CacheException(CacheResCode.INVALID_SPEC,
                            "coordination=GLOBAL 必须提供 HotKeyCoordinator，不静默退化为单节点模式");
                }
                yield new CaffeineDynamicLocalCache<>(spec, coordinator, nanoClock, versionReader, failOpen);
            }
        };
    }
}
