package com.asialjim.cache4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * key 编码器测试。
 *
 * <p>只保留默认的 {@link KeyEncoders#plain()}——它产出与 Spring Data Redis 原生一致的 key。
 * {@code canonical} / {@code tagged} / {@code identity} 已随重构方案 R2 移除。</p>
 *
 * @author Asial Jim
 */
class KeyEncodersTest {

    private final KeyEncoder encoder = KeyEncoders.plain();

    @Test
    @DisplayName("String / 数字原样输出，不加类型标签与长度前缀")
    void plainKeepsValueAsIs() {
        assertEquals("keep", encoder.encode("keep"));
        assertEquals("42", encoder.encode(42));
        assertEquals("42", encoder.encode(42L));
        assertEquals("true", encoder.encode(true));
        assertEquals("null", encoder.encode(null));
    }

    @Test
    @DisplayName("多参数用 : 连接")
    void plainJoinsParts() {
        assertEquals("a:1", encoder.encodeAll("a", 1));
        assertEquals("jim:42", encoder.encodeAll("jim", 42));
    }

    @Test
    @DisplayName("确定性：同样的输入永远得到同样的输出")
    void plainIsDeterministic() {
        assertEquals(encoder.encode("user:42"), encoder.encode("user:42"));
        assertEquals(encoder.encodeAll("a", "b"), encoder.encodeAll("a", "b"));
    }
}
