package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.Lookup;

/**
 * 版本 + 三态结果的组合，读路径一次 I/O 同时取回两者。
 *
 * @param revision 该 key 的版本
 * @param lookup   三态结果
 * @param <V>      值类型
 * @author Asial Jim
 */
public record VersionedLookup<V>(long revision, Lookup<V> lookup) {
}
