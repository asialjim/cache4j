package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.local.HotKeyAdmission;

import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略的本地缓存——<strong>Caffeine 存储</strong>版本。
 *
 * <p>与 {@code cache4j-local} 的 {@code HashMapDynamicLocalCache} 唯一的差别是父类：
 * 存储与 key 版本管理都继承自 {@link CaffeineLocalCache}，准入判定与它共用同一份
 * {@link HotKeyAdmission}。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class CaffeineDynamicLocalCache<V> extends CaffeineLocalCache<V> implements DynamicLocalCache<V> {

    private final HotKeyAdmission admission;

    CaffeineDynamicLocalCache(LocalCacheSpec spec, HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        super(spec, nanoClock);
        this.admission = new HotKeyAdmission(spec, coordinator, nanoClock, this::evict);
    }

    @Override
    public boolean accepts(CacheKey key) {
        return admission.accepts(key);
    }

    @Override
    public boolean coordinationReady() {
        return admission.coordinationReady();
    }

    @Override
    public boolean access(CacheKey key) {
        return admission.access(key);
    }

    @Override
    public void hotSync(CacheKey key) {
        admission.hotSync(key);
    }

    /**
     * @return 当前热点 key 集合（观测用）
     */
    public Set<CacheKey> hotKeySet() {
        return admission.hotKeySet();
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        if (!admission.accepts(key)) {
            return;
        }
        super.put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return admission.accepts(key) && super.putIfAbsent(key, value, ttl);
    }

    @Override
    public boolean putIfNewer(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return admission.accepts(key) && super.putIfNewer(key, value, ttl);
    }

    @Override
    public void clear() {
        admission.clear();
        super.clear();
    }

    @Override
    public void maintain() {
        super.maintain();
        admission.sweepCooldown();
    }
}
