package com.asialjim.cache4j.core;

/**
 * 回源加载器。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>异常<strong>原样向上抛</strong>，不包装成缓存异常；缓存库只负责统计并保证不写入任何值；</li>
 *   <li>返回 {@code null} 表示"数据不存在"，由编排层按 {@code nullTtl} 决定是否写空值缓存；</li>
 *   <li>不要在 loader 内对同一个 key 递归调用同一个缓存的 {@code lookup(key, loader)}，否则可能自等待。</li>
 * </ul>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

    /**
     * 回源读取。
     *
     * @param key key
     * @return 值；允许为 {@code null}（表示不存在）
     */
    V load(K key);
}
