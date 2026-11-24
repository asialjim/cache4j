package com.asialjim.cache4j.core.local;

/**
 * {@code STATIC} 策略的本地缓存<strong>接口</strong>。
 *
 * <p>语义：本地优先——命中直接返回，未命中查中央并回填；本地有容量上限与 TTL 淘汰。</p>
 *
 * <p><strong>本接口不带任何存储实现</strong>：具体存储（HashMap 分段 LRU、Caffeine、其他）
 * 由各模块提供实现，例如 {@code cache4j-local} 的 {@code HashMapStaticLocalCache}、
 * {@code cache4j-local-caffeine} 的 {@code CaffeineStaticLocalCache}。</p>
 *
 * <p>这样"策略"与"存储"是两个正交维度：换存储不影响策略语义，加策略不影响存储实现。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface StaticLocalCache<V> extends LocalCache<V> {
}
