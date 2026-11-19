package com.asialjim.cache4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CacheKey} 与 {@link LocalEntry} 的约束测试。
 *
 * @author Asial Jim
 */
class CacheKeyTest {

    @Test
    @DisplayName("合法 namespace")
    void shouldAcceptValidNamespace() {
        CacheKey key = new CacheKey("user:detail", "s2:42");
        assertEquals("user:detail:s2:42", key.logical());
    }

    @Test
    @DisplayName("namespace 不允许空、超长、非法字符、花括号")
    void shouldRejectInvalidNamespace() {
        assertThrows(CacheException.class, () -> new CacheKey("", "s1:a"));
        assertThrows(CacheException.class, () -> new CacheKey("x".repeat(65), "s1:a"));
        assertThrows(CacheException.class, () -> new CacheKey("user detail", "s1:a"));
        assertThrows(CacheException.class, () -> new CacheKey("{user}", "s1:a"));
    }

    @Test
    @DisplayName("encoded 不允许为空")
    void shouldRejectEmptyEncoded() {
        assertThrows(CacheException.class, () -> new CacheKey("user", ""));
        assertThrows(CacheException.class, () -> new CacheKey("user", null));
    }

    @Test
    @DisplayName("日志形式只暴露哈希，不打印原始 key")
    void maskedShouldNotLeakKey() {
        CacheKey key = new CacheKey("user", "s11:13800000000");
        String masked = key.masked();
        assertEquals(true, masked.startsWith("user#"));
        assertEquals(false, masked.contains("13800000000"));
    }

    @Test
    @DisplayName("VALUE 条目必须有值，NEGATIVE 条目必须无值")
    void localEntryInvariants() {
        LocalEntry<String> hit = LocalEntry.hit("v", 1L);
        assertEquals("v", hit.value());
        assertEquals(false, hit.isNegative());

        LocalEntry<String> negative = LocalEntry.negative(2L);
        assertEquals(true, negative.isNegative());
        assertEquals(null, negative.value());

        assertThrows(CacheException.class, () -> LocalEntry.hit(null, 1L));
        assertThrows(CacheException.class,
                () -> new LocalEntry<>("v", 1L, LocalEntry.Kind.NEGATIVE));
    }

    @Test
    @DisplayName("Lookup 三态语义")
    void lookupTriState() {
        assertEquals("v", Lookup.hit("v").optional().orElseThrow());
        assertEquals(true, Lookup.negative().isNegative());
        assertEquals(true, Lookup.miss().isMiss());
        assertEquals(false, Lookup.negative().optional().isPresent());
    }
}
