package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * envelope 编解码测试（纯逻辑，不需要 Redis）。
 *
 * @author Asial Jim
 */
class EnvelopeTest {

    @Test
    @DisplayName("真值 envelope 往返")
    void valueRoundTrip() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] raw = Envelope.value(payload).encode();

        assertEquals(4 + payload.length, raw.length);
        assertEquals(Envelope.MAGIC, raw[0]);
        assertEquals(Envelope.VERSION, raw[1]);

        Envelope decoded = Envelope.decode(raw);
        assertFalse(decoded.negative());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("负副本 envelope：flags 置位、payload 为空")
    void negativeRoundTrip() {
        byte[] raw = Envelope.negativeValue().encode();
        assertEquals(4, raw.length);
        assertTrue(Envelope.decode(raw).negative());
        assertEquals(0, Envelope.decode(raw).payload().length);
    }

    @Test
    @DisplayName("magic 不匹配 → CODEC_DECODE_FAILED（可能是别的系统写的数据）")
    void rejectAlienData() {
        byte[] alien = "{\"json\":true}".getBytes(StandardCharsets.UTF_8);
        CacheException e = assertThrows(CacheException.class, () -> Envelope.decode(alien));
        assertEquals(CacheResCode.CODEC_DECODE_FAILED, e.resCode());
    }

    @Test
    @DisplayName("版本不认识 → CODEC_DECODE_FAILED")
    void rejectUnknownVersion() {
        byte[] raw = Envelope.value(new byte[]{1}).encode();
        raw[1] = 0x09;
        assertThrows(CacheException.class, () -> Envelope.decode(raw));
    }

    @Test
    @DisplayName("长度不足 / null → CODEC_DECODE_FAILED，不当 miss")
    void rejectShort() {
        assertThrows(CacheException.class, () -> Envelope.decode(null));
        assertThrows(CacheException.class, () -> Envelope.decode(new byte[]{Envelope.MAGIC}));
    }
}
