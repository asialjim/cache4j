package com.asialjim.cache4j.core.local;

/**
 * {@code DYNAMIC} 策略的本地缓存<strong>接口</strong>。
 *
 * <p>语义：只让高频（热点）key 进入本地——本地窗口频次达标立即升热；本地不升热时由中央补偿查询；
 * 降热只认中央 {@code COOLING}。</p>
 *
 * <p><strong>本接口不带任何存储实现</strong>：热点判定（候选预选 + 精确窗口 + 升降热）与存储无关，
 * 各模块提供"热点判定 + 自己的存储"的实现，例如 {@code cache4j-local} 的
 * {@code HashMapDynamicLocalCache}、{@code cache4j-local-caffeine} 的
 * {@code CaffeineDynamicLocalCache}。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface DynamicLocalCache<V> extends LocalCache<V> {
}
