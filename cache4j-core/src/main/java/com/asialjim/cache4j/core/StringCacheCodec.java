package com.asialjim.cache4j.core;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 字符串编解码器。
 *
 * @author Asial Jim
 */
public final class StringCacheCodec implements CacheCodec<String> {

    /**
     * 单例。
     */
    public static final StringCacheCodec INSTANCE = new StringCacheCodec();

    private StringCacheCodec() {
    }

    @Override
    public byte[] encode(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
