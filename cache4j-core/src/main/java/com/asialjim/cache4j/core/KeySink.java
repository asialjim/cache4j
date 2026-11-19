package com.asialjim.cache4j.core;

/**
 * key 片段写入器：供 {@link KeyWritable} 实现方以类型化方式构造片段。
 *
 * <p>所有写入都会带上类型标签与长度，因此调用方无需自行转义分隔符。</p>
 *
 * @author Asial Jim
 */
public interface KeySink {

    /**
     * 写入字符串片段。
     *
     * @param value 值
     * @return this
     */
    KeySink text(String value);

    /**
     * 写入整数片段。
     *
     * @param value 值
     * @return this
     */
    KeySink number(long value);

    /**
     * 写入布尔片段。
     *
     * @param value 值
     * @return this
     */
    KeySink bool(boolean value);

    /**
     * 开始一个数组片段。
     *
     * @param size 元素个数
     * @return this
     */
    KeySink beginArray(int size);

    /**
     * 结束当前数组片段。
     *
     * @return this
     */
    KeySink endArray();
}
