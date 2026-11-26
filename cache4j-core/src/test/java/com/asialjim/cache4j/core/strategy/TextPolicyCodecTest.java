package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.DynamicSpec;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.local.LocalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认编码器：往返等价、null 字段、转义、损坏与不认识的版本都明确报错。
 *
 * @author Asial Jim
 */
class TextPolicyCodecTest {

    private final TextPolicyCodec codec = new TextPolicyCodec();

    @Test
    @DisplayName("完整字段往返等价")
    void roundTripFullPolicy() {
        GlobalSpec global = GlobalSpec.of(Duration.ofSeconds(10), 100L, 4);
        DynamicSpec dynamic = DynamicSpec.defaults().withGlobal(global);
        StoredPolicy policy = new StoredPolicy("user:detail", "用户详情", LocalStrategy.DYNAMIC,
                5_000L, Duration.ofSeconds(30), 10, VersionSpec.defaults(), dynamic,
                Duration.ofMinutes(30), Duration.ofSeconds(30), true,
                7L, 1_700_000_000_000L, "ops:zhangsan", "扩容");

        StoredPolicy decoded = codec.decode(codec.encode(policy));

        assertEquals(policy, decoded);
    }

    @Test
    @DisplayName("null 字段往返仍是 null（表示用内建默认）")
    void roundTripNullFields() {
        StoredPolicy policy = new StoredPolicy("x:y", null, LocalStrategy.NONE,
                null, null, null, null, null, null, null, null, 0L, 0L, null, null);

        StoredPolicy decoded = codec.decode(codec.encode(policy));

        assertEquals(policy, decoded);
        assertNull(decoded.description());
        assertNull(decoded.property().getLocalMaxSize(), "未设置的字段往返后仍是 null");
        assertNull(decoded.dynamic());
    }

    @Test
    @DisplayName("值里的换行与反斜杠被转义，不会破坏逐行格式")
    void escapesNewlinesAndBackslashes() {
        StoredPolicy policy = new StoredPolicy("x:y", "第一行\n第二行\\结尾", LocalStrategy.STATIC,
                1_000L, Duration.ofSeconds(30), 10, VersionSpec.defaults(), null,
                Duration.ofMinutes(30), Duration.ofSeconds(30), true, 1L, 0L, null, null);

        byte[] raw = codec.encode(policy);
        String text = new String(raw, StandardCharsets.UTF_8);
        assertTrue(text.lines().count() >= 16, "一个字段一行");

        StoredPolicy decoded = codec.decode(raw);
        assertEquals("第一行\n第二行\\结尾", decoded.description());
    }

    @Test
    @DisplayName("不认识的 schema 版本：明确报错，不尽力解析")
    void rejectsUnknownSchema() {
        byte[] raw = "schema=99\nnamespace=x:y\n".getBytes(StandardCharsets.UTF_8);
        CacheException e = assertThrows(CacheException.class, () -> codec.decode(raw));
        assertTrue(e.getMessage().contains("不支持的策略记录 schema=99"), e.getMessage());
    }

    @Test
    @DisplayName("编码为空或格式错误：报错")
    void rejectsBrokenEncoding() {
        assertThrows(CacheException.class, () -> codec.decode(new byte[0]));
        assertThrows(CacheException.class,
                () -> codec.decode("这不是 key=value".getBytes(StandardCharsets.UTF_8)));
        assertThrows(CacheException.class,
                () -> codec.decode("schema=1\nmaxSize=abc\n".getBytes(StandardCharsets.UTF_8)));
    }
}
