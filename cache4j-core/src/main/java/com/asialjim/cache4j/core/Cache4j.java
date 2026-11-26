package com.asialjim.cache4j.core;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存通用契约——<strong>本地、中央、门面三层共用同一个顶层接口</strong>。
 *
 * <p>命名带 {@code 4j} 后缀而不是叫 {@code Cache}：Spring 有
 * {@code org.springframework.cache.Cache}、Caffeine 有
 * {@code com.github.benmanes.caffeine.cache.Cache}，同名会让使用方的 import 频繁冲突。</p>
 *
 * <p>层级关系（与 {@code cache4j-2} 的结构一致）：</p>
 *
 * <pre>
 * Cache4j&lt;K, V&gt;                   顶层契约
 *    ├── LocalCache&lt;K, V&gt;          本地层（进程内）
 *    ├── CentralCache&lt;K, V&gt;        中央层（跨进程）
 *    └── Cache4jImpl&lt;K, V&gt;         门面：组合上面两层 + SingleFlight + Reconciler
 * </pre>
 *
 * <p>当前 {@link LocalCache} / {@link CentralCache} 仍以 {@code CacheKey} 为 key（泛型只有 V），
 * 后续会迁移成 {@code <K, V>}，与顶层对齐。</p>
 *
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有实现必须线程安全；</li>
 *   <li>{@link #lookup} 不做回源；回源只发生在带 {@link CacheLoader} 的
 *       {@code Cache4j.lookup(key, loader)} 中；</li>
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
public interface Cache4j<K, V> extends AutoCloseable {

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
     * 读取缓存，未命中时用 loader 回源并回填；同一个 key 的并发回源只执行一次。
     *
     * <p>三层都可能实现它：门面做"本地 → 中央 → 回源"，中央做"回源 + 单飞"，
     * 本地层通常直接委托给上层（默认抛 {@link UnsupportedOperationException}）。</p>
     *
     * @param key    key
     * @param loader 回源加载器
     * @return 三态结果
     */
    default Lookup<V> lookup(K key, CacheLoader<K, V> loader) {
        throw new UnsupportedOperationException(
                getClass().getName() + " 不支持带 loader 的读取");
    }

    /**
     * @return 该 namespace 的完整缓存策略（本地层 / 中央层 / 门面共享同一份实例）
     */
    CacheStrategy cacheStrategy();

    /**
     * @return 本地策略
     */
    default com.asialjim.cache4j.core.local.LocalStrategy strategy() {
        CacheStrategy strategy = cacheStrategy();
        return strategy == null ? null : strategy.localStrategy();
    }

    /**
     * @return 本地缓存视图（诊断用）；非门面实现返回 {@code null}
     */
    default com.asialjim.cache4j.core.local.LocalCache<V> local() {
        return null;
    }

    /**
     * @return 中央缓存视图；非门面实现返回 {@code null}
     */
    default com.asialjim.cache4j.core.central.CentralCache<V> central() {
        return null;
    }

    /**
     * 释放资源；默认无资源，幂等。
     */
    @Override
    default void close() {
    }
}
