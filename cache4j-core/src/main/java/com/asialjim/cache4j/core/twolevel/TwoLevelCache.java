package com.asialjim.cache4j.core.twolevel;

import com.asialjim.cache4j.core.Cache;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalStrategy;

/**
 * 二级缓存门面（SPI）：编排本地缓存与中央缓存。
 *
 * <p>做成接口是为了让业务可以替换默认编排（{@link DefaultTwoLevelCache}），而编排能力本身
 * 属于框架无关的核心。</p>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface TwoLevelCache<K, V> extends Cache<K, V> {

    /**
     * 读取缓存，未命中时用 loader 回源并回填；同一个 key 的并发回源只执行一次。
     *
     * @param key    key
     * @param loader 回源加载器
     * @return 三态结果
     */
    Lookup<V> lookup(K key, CacheLoader<K, V> loader);

    /**
     * @return 本地策略
     */
    LocalStrategy strategy();

    /**
     * @return 本地缓存视图（诊断用）
     */
    LocalCache<V> local();

    /**
     * @return 中央缓存视图
     */
    CentralCache<V> central();

    /**
     * 构造器入口。
     *
     * @param namespace 缓存名
     * @param <K>       key 类型
     * @param <V>       值类型
     * @return 构建器
     */
    static <K, V> DefaultTwoLevelCache.Builder<K, V> builder(String namespace) {
        return new DefaultTwoLevelCache.Builder<>(namespace);
    }
}
