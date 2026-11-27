package com.asialjim.cache4j.local.caffeine;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Cache4jKeyVersion;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.local.AbstractStaticLocalCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code STATIC} 策略的 Caffeine 实现。
 *
 * <p>STATIC 的语义是"本地优先"：本地命中直接返回，未命中查中央并回填，本地有容量与 TTL 淘汰。
 * 因此<strong>读和写都会落到本地</strong>（与 {@code DYNAMIC} 相反——那边只允许读路径回填）。</p>
 *
 * <p>版本管理（版本表、分区索引、对账）全部继承自
 * {@link AbstractStaticLocalCache4j}→{@code AbstractVersionedLocalCache4j}，
 * 本类只负责"怎么存"。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public class CaffeineStaticLocalCache4j<V> extends AbstractStaticLocalCache4j<V> {

    private final CacheStrategy strategy;
    private final CaffeineStore4j<V> store;

    /**
     * @param strategy        缓存策略
     * @param versionDetector 中央版本探测器
     */
    public CaffeineStaticLocalCache4j(CacheStrategy strategy, Cache4jKeyVersionDetector versionDetector) {
        super(versionDetector);
        this.strategy = strategy;
        this.store = new CaffeineStore4j<V>(strategy,
                key -> whenLocalCacheExpired(key, strategy));
    }

    @Override
    public CacheStrategy getCacheStrategy() {
        return strategy;
    }

    // ------------------------------------------------------------------ StrategyCache4j

    @Override
    public Lookup<V> lookup(Cache4jKey key, CacheStrategy strategySnapshot) {
        return store.load(versioned(key, strategySnapshot));
    }

    @Override
    public V get(Cache4jKey key, Supplier<V> valueLoader, CacheStrategy strategySnapshot) {
        Lookup<V> local = lookup(key, strategySnapshot);
        if (!local.isMiss()) {
            return local.optional().orElse(null);
        }
        V value = valueLoader == null ? null : valueLoader.get();
        // STATIC：未命中就回填（含负副本），保证下一次读命中
        Cache4jKey versioned = versioned(key, strategySnapshot);
        if (value == null) {
            store.storeNegative(versioned);
        } else {
            store.store(versioned, value);
        }
        return value;
    }

    @Override
    public void put(Cache4jKey key, V value, CacheStrategy strategySnapshot) {
        Cache4jKey versioned = versioned(key, strategySnapshot);
        if (value == null) {
            store.storeNegative(versioned);
        } else {
            store.store(versioned, value);
        }
    }

    @Override
    public void putSync(Cache4jKey key, V value, CacheStrategy strategySnapshot) {
        // STATIC 的 put 本来就是同步写本地，两者等价
        put(key, value, strategySnapshot);
    }

    @Override
    public void evict(Cache4jKey key, CacheStrategy strategySnapshot) {
        delete(key.key(), strategySnapshot);
    }

    @Override
    public void clear(CacheStrategy strategySnapshot) {
        store.clear();
    }

    // ------------------------------------------------------------------ 父类钩子

    @Override
    protected void delete(String key, CacheStrategy strategySnapshot) {
        store.delete(key);
    }

    /**
     * 把逻辑 key 换成带版本的 key：版本进 key，版本一变 key 就变，旧副本自然不命中。
     */
    private Cache4jKey versioned(Cache4jKey key, CacheStrategy strategySnapshot) {
        Cache4jKeyVersion version = keyVersion(key.key(), strategySnapshot);
        //noinspection ConstantValue
        if (Objects.nonNull(version)) {
            return key;
        }
        return new Cache4jKey(key.namespace(), key.key(), version);
    }

    /**
     * @return 当前条目数（观测用）
     */
    public long estimatedSize() {
        return store.estimatedSize();
    }

    @Override
    public String toString() {
        return "CaffeineStaticLocalCache4j{namespace=" + strategy.namespace() + '}';
    }
}
