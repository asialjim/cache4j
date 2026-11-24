package com.asialjim.cache4j.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略本地缓存的<strong>骨架</strong>：组合"热点准入"与"存储"。
 *
 * <p>准入判定（何时升热、何时降热、何时上报）全部委托给 {@link HotKeyAdmission}——
 * 它不碰存储，所以同一份逻辑能服务 HashMap 与 Caffeine 两种实现。</p>
 *
 * <p>本类只做两件事：把准入的判定接到 {@code LocalCache} 的钩子上
 * （{@code accepts} / {@code access} / {@code hotSync} / {@code maintain}），其余全部委托给存储。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public abstract class AbstractDynamicLocalCache<V> implements DynamicLocalCache<V> {

    private final LocalCache<V> delegate;
    private final LocalCacheSpec spec;
    private final HotKeyAdmission admission;

    /**
     * @param delegate    存储层（HashMap / Caffeine），其 {@code spec} 与本对象一致
     * @param spec        配置（策略必须为 {@code DYNAMIC}）
     * @param coordinator 热 key 协调器；{@code coordination=GLOBAL} 时必填
     * @param nanoClock   单调时钟
     */
    protected AbstractDynamicLocalCache(LocalCache<V> delegate, LocalCacheSpec spec,
                                        HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.spec = spec;
        this.admission = new HotKeyAdmission(spec, coordinator, nanoClock, delegate::evict);
    }

    // ------------------------------------------------------------------ 准入判定

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

    @Override
    public void maintain() {
        delegate.maintain();
        admission.sweepCooldown();
    }

    /**
     * @return 当前热点 key 集合（观测用）
     */
    public java.util.Set<CacheKey> hotKeySet() {
        return admission.hotKeySet();
    }

    // ------------------------------------------------------------------ 委托存储

    @Override
    public LocalStrategy strategy() {
        return delegate.strategy();
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public java.util.Set<CacheKey> keys() {
        return delegate.keys();
    }

    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        return delegate.lookup(key);
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return delegate.containsKey(key);
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        if (!admission.accepts(key)) {
            // 冷 key 不落本地：写不计频次，也就不可能因为它"热"。需要 read-your-write 请用 STATIC。
            return;
        }
        delegate.put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return admission.accepts(key) && delegate.putIfAbsent(key, value, ttl);
    }

    @Override
    public boolean putIfNewer(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return admission.accepts(key) && delegate.putIfNewer(key, value, ttl);
    }

    @Override
    public LocalEntry<V> peek(CacheKey key) {
        return delegate.peek(key);
    }

    @Override
    public void evict(CacheKey key) {
        // 只失效数据，不动热点身份：热点由"读频次"决定，与值是否新鲜无关。
        delegate.evict(key);
    }

    @Override
    public void clear() {
        admission.clear();
        delegate.clear();
    }

    @Override
    public void onEntryRemoved(java.util.function.Consumer<CacheKey> listener) {
        delegate.onEntryRemoved(listener);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
