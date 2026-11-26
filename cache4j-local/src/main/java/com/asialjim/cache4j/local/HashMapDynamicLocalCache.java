package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略的本地缓存——<strong>HashMap 存储</strong>版本。
 *
 * <p><strong>DYNAMIC 就是 STATIC 加上"准入"</strong>：两者在存储与 key 版本管理上完全相同
 * （分段 LRU、{@code LocalEntry.revision}、{@code putIfNewer} 的比较语义），差别只在
 * "谁有资格待在本地"。所以这里直接继承 {@link HashMapLocalCache}，只覆写准入相关的钩子。</p>
 *
 * <p>准入判定本身在 {@link HotKeyAdmission}——它不碰存储，一份逻辑同时服务
 * HashMap 与 Caffeine 两种实现。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class HashMapDynamicLocalCache<V> extends HashMapLocalCache<V> implements DynamicLocalCache<V> {

    private static final Logger LOG = LoggerFactory.getLogger(HashMapDynamicLocalCache.class);

    private final HotKeyAdmission admission;

    HashMapDynamicLocalCache(LocalCacheSpec spec, HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        super(spec, nanoClock);
        this.admission = new HotKeyAdmission(spec, coordinator, nanoClock, this::evict);
    }

    // ------------------------------------------------------------------ 准入钩子

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

    // ------------------------------------------------------------------ 写路径的准入守卫（D23）

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        // 冷 key 不落本地：写不计频次，也就不可能因为它"热"。需要 read-your-write 请用 STATIC。
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

    // ------------------------------------------------------------------ 生命周期

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
