package com.asialjim.cache4j.core.local;

/**
 * {@code NONE} 策略的本地缓存<strong>接口</strong>。
 *
 * <p>语义：本地永不存值，读请求直接落到中央缓存；中央命中的值也不回填本地。</p>
 *
 * <p>实现应当把"写入"变成无操作、"读取"恒为未命中，同时保留版本与统计接口的空实现。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface NoneLocalCache<V> extends LocalCache<V> {
}
