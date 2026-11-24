package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * {@code DYNAMIC} 策略的本地缓存——<strong>HashMap 存储</strong>版本。
 *
 * <p>组合关系：热点准入（{@link HotKeyAdmissionLocalCache}）+ 分段 LRU 存储
 * （{@link HashMapLocalCache}）。策略语义在准入层，存储细节在存储层，两者互不知道对方的实现。</p>
 *
 * <p>换存储（例如 {@code cache4j-local-caffeine} 的 {@code CaffeineDynamicLocalCache}）
 * 只需换掉里面的存储实现，准入逻辑完全复用。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class HashMapDynamicLocalCache<V> implements DynamicLocalCache<V> {

    private final LocalCache<V> delegate;

    HashMapDynamicLocalCache(LocalCacheSpec spec, HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        this.delegate = new HotKeyAdmissionLocalCache<>(new HashMapLocalCache<>(spec, nanoClock), spec,
                coordinator, nanoClock);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public LocalStrategy strategy() {
        return delegate.strategy();
    }

    @Override
    public boolean available() {
        return delegate.available();
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
        delegate.put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        return delegate.putIfAbsent(key, value, ttl);
    }

    @Override
    public void evict(CacheKey key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public LocalCacheSpec spec() {
        return delegate.spec();
    }

    @Override
    public Set<CacheKey> keys() {
        return delegate.keys();
    }

    @Override
    public boolean access(CacheKey key) {
        return delegate.access(key);
    }

    @Override
    public void hotSync(CacheKey key) {
        delegate.hotSync(key);
    }

    @Override
    public void maintain() {
        delegate.maintain();
    }

    @Override
    public boolean accepts(CacheKey key) {
        return delegate.accepts(key);
    }

    @Override
    public void onEntryRemoved(Consumer<CacheKey> listener) {
        delegate.onEntryRemoved(listener);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
