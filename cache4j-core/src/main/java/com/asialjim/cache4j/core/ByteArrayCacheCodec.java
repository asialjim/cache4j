package com.asialjim.cache4j.core;

/**
 * 原样字节编解码器。
 *
 * @author Asial Jim
 */
public final class ByteArrayCacheCodec implements CacheCodec<byte[]> {

    /**
     * 单例。
     */
    public static final ByteArrayCacheCodec INSTANCE = new ByteArrayCacheCodec();

    private ByteArrayCacheCodec() {
    }

    @Override
    public byte[] encode(byte[] value) {
        return value.clone();
    }

    @Override
    public byte[] decode(byte[] bytes) {
        return bytes.clone();
    }
}
