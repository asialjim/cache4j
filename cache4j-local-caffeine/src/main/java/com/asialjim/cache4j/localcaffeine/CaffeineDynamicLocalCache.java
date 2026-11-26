package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.local.HotKeyAdmission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(CaffeineDynamicLocalCache.class);

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

    /**
     * 读本地：<strong>热 key 判定在本地层内部完成</strong>，门面不需要知道"什么是热 key"。
     *
     * <p>冷 key 返回 {@link Lookup#miss()}——用"未命中"天然表达"本地不服务这个 key"，
     * 于是门面的后续动作（去查中央）与"本地可用但未命中"完全相同，不需要任何分支判断。</p>
     *
     * @param key key
     * @return 三态结果
     */
    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        boolean hot = admission.access(key);        // 记录读频次（可能在此升热）
        admission.hotSync(key);                     // 到点上报热点
        if (!hot) {
            LOG.debug("[{}]-读-[{}]-{}", "动态本地缓存", key.masked(), "冷key，miss，硬读中央缓存");
            return Lookup.miss();
        }
        Lookup<LocalEntry<V>> result = super.lookup(key);
        if (result.isHit()) {
            LOG.debug("[{}]-读-[{}]-{}", "动态本地缓存", key.masked(), "热key，命中");
        } else if (result.isMiss()) {
            LOG.debug("[{}]-读-[{}]-{}", "动态本地缓存", key.masked(), "热key，miss，硬读中央缓存");
        }
        return result;
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
