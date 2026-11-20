package com.asialjim.cache4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规范 key 编码测试。
 *
 * @author Asial Jim
 */
class KeyEncodersTest {

    private final KeyEncoder encoder = KeyEncoders.canonical();

    @Test
    @DisplayName("类型标签区分同值不同类型：42L / 42 / \"42\" 互不相等")
    void shouldTagTypes() {
        assertEquals("l2:42", encoder.encode(42L));
        assertEquals("i2:42", encoder.encode(42));
        assertEquals("s2:42", encoder.encode("42"));
        assertNotEquals(encoder.encode(42L), encoder.encode("42"));
        assertNotEquals(encoder.encode(42), encoder.encode(42L));
    }

    @Test
    @DisplayName("基础类型编码")
    void shouldEncodeScalars() {
        assertEquals("n;", encoder.encode(null));
        assertEquals("B1:1", encoder.encode(true));
        assertEquals("B1:0", encoder.encode(false));
        assertEquals("h2:42", encoder.encode((short) 42));
        assertEquals("y2:42", encoder.encode((byte) 42));
        assertEquals("C1:a", encoder.encode('a'));
        assertEquals("d3:1.5", encoder.encode(1.5D));
    }

    @Test
    @DisplayName("BigDecimal 去尾零、禁止科学计数法")
    void shouldNormalizeBigDecimal() {
        assertEquals(encoder.encode(new BigDecimal("1.0")), encoder.encode(new BigDecimal("1.00")));
        assertEquals("D1:0", encoder.encode(new BigDecimal("0.000")));
        assertEquals("D4:1.25", encoder.encode(new BigDecimal("1.2500")));
    }

    @Test
    @DisplayName("Set / Map 与迭代顺序无关")
    void shouldBeOrderIndependent() {
        Set<Object> first = new LinkedHashSet<>(List.of("a", 1L));
        Set<Object> second = new LinkedHashSet<>(List.of(1L, "a"));
        assertEquals(encoder.encode(first), encoder.encode(second));

        Map<Object, Object> mapA = new LinkedHashMap<>();
        mapA.put("k1", 1L);
        mapA.put("k2", 2L);
        Map<Object, Object> mapB = new LinkedHashMap<>();
        mapB.put("k2", 2L);
        mapB.put("k1", 1L);
        assertEquals(encoder.encode(mapA), encoder.encode(mapB));
    }

    @Test
    @DisplayName("集合保留迭代顺序（List 语义）")
    void shouldKeepListOrder() {
        assertNotEquals(encoder.encode(List.of("a", "b")), encoder.encode(List.of("b", "a")));
    }

    @Test
    @DisplayName("超长段做 SHA-256 段哈希，长度有界")
    void shouldHashLongSegment() {
        String encoded = encoder.encode("x".repeat(200));
        assertTrue(encoded.startsWith("#43:"), () -> "实际：" + encoded);
        assertEquals(47, encoded.length());
        // 同样的输入必须可复现
        assertEquals(encoded, encoder.encode("x".repeat(200)));
    }

    @Test
    @DisplayName("不支持的类型直接报错，不静默回退 toString")
    void shouldRejectUnsupportedType() {
        CacheException exception = assertThrows(CacheException.class, () -> encoder.encode(new Object()));
        assertEquals(CacheResCode.KEY_ENCODE_FAILED, exception.resCode());
    }

    @Test
    @DisplayName("identity 编码器只接受 String")
    void identityShouldAcceptStringOnly() {
        assertEquals("abc", KeyEncoders.identity().encode("abc"));
        assertThrows(CacheException.class, () -> KeyEncoders.identity().encode(1));
    }

    @Test
    @DisplayName("多参数 key 编码为数组")
    void shouldEncodeMultipleParts() {
        assertEquals("a2[s1:a,i1:1]", encoder.encodeAll("a", 1));
    }

    @Test
    @DisplayName("tagged：类型与长度放在 key 末尾，不打断层级")
    void taggedKeepsTypeInfoAtTheEnd() {
        KeyEncoder encoder = KeyEncoders.tagged();
        assertEquals("keep:s4", encoder.encode("keep"));
        assertEquals("42:i2", encoder.encode(42));
        assertEquals("42:l2", encoder.encode(42L));
        assertEquals("true:B4", encoder.encode(true));
        assertEquals("null:n4", encoder.encode(null));
    }

    @Test
    @DisplayName("tagged：多参数逐段带后缀，值仍紧跟在命名空间之后")
    void taggedWithMultipleParts() {
        KeyEncoder encoder = KeyEncoders.tagged();
        assertEquals("a:s1:1:i1", encoder.encodeAll("a", 1));
        assertEquals("jim:s3:42:i2", encoder.encodeAll("jim", 42));
    }

    @Test
    @DisplayName("tagged：不同类型同值不撞 key（这是它相对 plain 的价值）")
    void taggedDistinguishesTypes() {
        KeyEncoder encoder = KeyEncoders.tagged();
        assertNotEquals(encoder.encode("42"), encoder.encode(42));
        assertEquals("42:s2", encoder.encode("42"));
        assertEquals("42:i2", encoder.encode(42));
    }
}
