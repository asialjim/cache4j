package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略的本地缓存——<strong>HashMap 存储</strong>版本。
 *
 * <p>准入逻辑（候选预选 + 16 片窗口 + 升降热）全部在 {@link AbstractDynamicLocalCache} 里，
 * 本类只负责提供"用哪种存储"——分段 LRU（{@link HashMapLocalCache}）。</p>
 *
 * <p>换存储就是换这一个构造参数：{@code cache4j-local-caffeine} 的
 * {@code CaffeineDynamicLocalCache} 除此之外完全一样。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class HashMapDynamicLocalCache<V> extends AbstractDynamicLocalCache<V> {

    HashMapDynamicLocalCache(LocalCacheSpec spec, HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        super(new HashMapLocalCache<>(spec, nanoClock), spec, coordinator, nanoClock);
    }
}
