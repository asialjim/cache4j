package com.asialjim.cache4j.core.strategy;


/**
 * 策略值的编解码：写穿自举缓存时要把 {@link StoredPolicy} 编成缓存 value 的字节。
 *
 * <p>放在本模块（而不是 {@code cache4j-core}）是为了守住"<strong>核心不碰字节</strong>"的
 * 既有约束——编码是接入层的事。</p>
 *
 * <p>实现必须<strong>可逆</strong>：{@code decode(encode(p))} 与 {@code p} 等价。</p>
 *
 * @author Asial Jim
 */
public interface PolicyCodec {

    /**
     * @param policy 策略
     * @return 字节
     */
    byte[] encode(StoredPolicy policy);

    /**
     * @param raw 字节
     * @return 策略
     * @throws com.asialjim.cache4j.core.CacheException 编码损坏（{@code CODEC_DECODE_FAILED}）
     */
    StoredPolicy decode(byte[] raw);
}
