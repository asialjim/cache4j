package com.asialjim.cache4j.core.coordination;

import com.asialjim.cache4j.core.Lookup;

import java.util.function.Supplier;

/**
 * 单飞：<strong>同一个 key 在任一时刻只有一个写者</strong>。
 *
 * <p>它不只是"合并请求"，而是唯一性语义：</p>
 * <ul>
 *   <li><strong>进程角度</strong>：同一 key 只有一个线程执行加载并写缓存，其余线程等它的结果；</li>
 *   <li><strong>集群角度</strong>：同一 key 最终只落一份值（由中央的原子写保证），不会两个节点各写一份互相覆盖。</li>
 * </ul>
 *
 * <p>因此它是一个<strong>独立契约</strong>，不藏在 {@code get(key, loader)} 的语义里：可以被单测、
 * 可以替换（要跨节点保护就换成分布式锁实现），也不必与 {@code get} 的返回类型纠缠。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>加载抛异常时<strong>不缓存异常</strong>——本次所有等待者收到同一异常，下次调用重新尝试；</li>
 *   <li>加载返回 {@link Lookup#negative()} 是"确认不存在"，<strong>负结果同样被合并</strong>，
 *       不会因为结果是 null 就击穿；</li>
 *   <li>不同 key 之间完全并行，互不阻塞。</li>
 * </ul>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface SingleFlight<K, V> {

    /**
     * 同一个 key 只有一个线程执行 {@code loader}；其余线程等待并复用结果。
     *
     * @param key    key
     * @param loader 完整的"加载 + 写缓存"过程，返回三态结果
     * @return 加载结果（正命中 / 负命中）
     */
    Lookup<V> once(K key, Supplier<Lookup<V>> loader);

    /**
     * 取消所有进行中的加载（缓存关闭时调用，避免等待者永久阻塞）。
     */
    default void close() {
    }
}
