package com.asialjim.cache4j.core;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存通用契约。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有实现必须线程安全；</li>
 *   <li>{@link #lookup} 不做回源；回源只发生在带 {@link CacheLoader} 的
 *       {@code TwoLevelCache.lookup(key, loader)} 中；</li>
 *   <li>{@code put(key, null)} 的语义见负缓存规则：启用空值缓存时写负副本，否则等价于
 *       {@link #evict}；</li>
 *   <li>{@link #putIfAbsent} 在中央不可用且 {@code failOpen=true} 时返回 {@code false}
 *       （无法保证原子性），不静默当成"本地成功"；</li>
 *   <li>不提供 {@code size()}：跨进程的"总大小"没有稳定语义，需要时看
 *       {@link CacheStats#estimatedSize()}（仅本地/近似）；</li>
 *   <li>{@link #close()} 必须幂等。</li>
 * </ul>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface Cache<K, V> extends AutoCloseable {

    /**
     * @return 缓存名（namespace）
     */
    String name();

    /**
     * 读取缓存，不回源。
     *
     * @param key key
     * @return 三态结果
     */
    Lookup<V> lookup(K key);

    /**
     * 读取缓存并返回 {@link Optional} 视图；负命中与未命中都返回空。
     *
     * @param key key
     * @return 值
     */
    default Optional<V> get(K key) {
        return lookup(key).optional();
    }

    /**
     * @param key key
     * @return 是否存在可用结论（真值或已确认不存在）
     */
    boolean containsKey(K key);

    /**
     * 写入。
     *
     * @param key   key
     * @param value 值；{@code null} 表示"确认不存在"
     * @param ttl   存活时间；{@code null} 表示使用缓存默认值
     */
    void put(K key, V value, Duration ttl);

    /**
     * 写入，使用默认 TTL。
     *
     * @param key   key
     * @param value 值
     */
    default void put(K key, V value) {
        put(key, value, null);
    }

    /**
     * 原子"不存在才写入"；负副本视为已存在。
     *
     * @param key   key
     * @param value 值
     * @param ttl   存活时间；{@code null} 表示默认
     * @return 是否写入成功
     */
    boolean putIfAbsent(K key, V value, Duration ttl);

    /**
     * 失效单个 key。
     *
     * @param key key
     */
    void evict(K key);

    /**
     * 清空该缓存名下的全部数据。
     */
    void clear();

    /**
     * 释放资源；默认无资源，幂等。
     */
    @Override
    default void close() {
    }
}
