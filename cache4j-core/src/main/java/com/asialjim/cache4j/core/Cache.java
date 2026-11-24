package com.asialjim.cache4j.core;

/**
 * 旧名兼容壳：方法集与语义全部在 {@link Cache4j}。
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @deprecated 用 {@link Cache4j}；本接口只为平滑迁移保留，后续删除。
 * @author Asial Jim
 */
@Deprecated
public interface Cache<K, V> extends Cache4j<K, V> {
}
