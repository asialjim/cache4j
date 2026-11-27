package com.asialjim.cache4j.key;

import com.asialjim.cache4j.KeyEncoder;

public final class StringKeyEncoder implements KeyEncoder {
    public static final KeyEncoder INSTANCE = new StringKeyEncoder();

    @Override
    public String encode(Object key) {
        return String.valueOf(key);
    }
}
