package com.asialjim.cache4j.local.caffeine;

import com.asialjim.cache4j.Cache4jHotKeyDetector;
import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.local.AbstractDynamicLocalCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

/**
 * {@code DYNAMIC} 策略的 Caffeine 实现。
 *
 * <p>热 key 准入、升降温、上报、版本对账<strong>全部</strong>在
 * {@link AbstractDynamicLocalCache4j} 里，本类只提供四件事：</p>
 * <ul>
 *   <li>{@link #load} —— 从 Caffeine 读</li>
 *   <li>{@link #store} / {@link #storeNegative} —— 写 Caffeine（<strong>只会被读路径的回填调用</strong>）</li>
 *   <li>{@link #clearStorage} / {@link #delete} —— 清空与单条删除</li>
 * </ul>
 *
 * <p>注意 {@code put} / {@code putSync} 在父类里是 {@code final} 空实现——
 * {@code DYNAMIC} 下写不落本地，本地副本的唯一来源是读路径回填。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public class CaffeineDynamicLocalCache4j<V> extends AbstractDynamicLocalCache4j<V> {

    private final CacheStrategy strategy;
    private final CaffeineStore4j<V> store;

    /**
     * @param strategy        缓存策略
     * @param versionDetector 中央版本探测器
     * @param hotKeyDetector  中央热 key 探测器；{@code null} 表示 {@code coordination=LOCAL}
     */
    public CaffeineDynamicLocalCache4j(CacheStrategy strategy,
                                       Cache4jKeyVersionDetector versionDetector,
                                       Cache4jHotKeyDetector hotKeyDetector) {
        super(versionDetector, hotKeyDetector);
        this.strategy = strategy;
        this.store = new CaffeineStore4j<>(strategy,
                key -> whenLocalCacheExpired(key, strategy));
    }

    @Override
    public CacheStrategy getCacheStrategy() {
        return strategy;
    }

    // ------------------------------------------------------------------ 存储钩子

    @Override
    protected Lookup<V> load(Cache4jKey key) {
        return store.load(key);
    }

    @Override
    protected void store(Cache4jKey key, V value) {
        store.store(key, value);
    }

    @Override
    protected void storeNegative(Cache4jKey key) {
        store.storeNegative(key);
    }

    @Override
    protected void clearStorage() {
        store.clear();
    }

    @Override
    protected void delete(String key, CacheStrategy strategySnapshot) {
        store.delete(key);
    }

    /**
     * @return 当前条目数（观测用）
     */
    public long estimatedSize() {
        return store.estimatedSize();
    }

    @Override
    public String toString() {
        return "CaffeineDynamicLocalCache4j{namespace=" + strategy.namespace()
                + ", hotKey=" + (hotKeyReady() ? "GLOBAL" : "LOCAL") + '}';
    }
}
