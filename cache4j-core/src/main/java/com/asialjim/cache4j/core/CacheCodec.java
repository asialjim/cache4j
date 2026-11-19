package com.asialjim.cache4j.core;

/**
 * 值编解码 SPI：中央缓存必须把值序列化为字节，编解码不绑定任何 JSON 库。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>编解码失败必须抛 {@link CacheResCode#CODEC_ENCODE_FAILED} /
 *       {@link CacheResCode#CODEC_DECODE_FAILED}，<strong>不允许</strong>当成 miss 静默吞掉 ——
 *       数据损坏必须可见；</li>
 *   <li>实现必须线程安全；</li>
 *   <li>JSON 编解码不放进核心，由 {@code util4j} 或后续 {@code cache4j-codec-*} 提供。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface CacheCodec<V> {

    /**
     * 序列化；{@code null} 由 envelope 表达，不进入本方法。
     *
     * @param value 值
     * @return 字节
     */
    byte[] encode(V value);

    /**
     * 反序列化。
     *
     * @param bytes 字节
     * @return 值
     */
    V decode(byte[] bytes);
}
