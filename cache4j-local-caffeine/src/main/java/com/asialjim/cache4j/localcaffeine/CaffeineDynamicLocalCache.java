package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.local.AbstractDynamicLocalCache;

import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略的本地缓存——<strong>Caffeine 存储</strong>版本。
 *
 * <p>与 {@code cache4j-local} 的 {@code HashMapDynamicLocalCache} 唯一的差别是存储实现：
 * 准入逻辑（候选预选 + 16 片窗口 + 升降热）全部在 {@link AbstractDynamicLocalCache} 里，一份。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class CaffeineDynamicLocalCache<V> extends AbstractDynamicLocalCache<V> {

    CaffeineDynamicLocalCache(LocalCacheSpec spec, HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        super(new CaffeineLocalCache<>(spec, nanoClock), spec, coordinator, nanoClock);
    }
}
