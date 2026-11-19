package com.asialjim.cache4j.core;

/**
 * 业务自定义 key 片段：实现方决定如何把自身写成一个稳定的 key 片段。
 *
 * <p>用于规范编码不认识的类型。刻意不提供"回退到 {@code toString()}"的默认行为 ——
 * 那种"看起来能用、重启后全部 miss"的隐式回退比直接报错危险得多。</p>
 *
 * @author Asial Jim
 */
public interface KeyWritable {

    /**
     * 把自身写入 key 片段。
     *
     * @param sink 片段写入器
     */
    void writeKey(KeySink sink);
}
