package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;

/**
 * Redis 值格式（<strong>适配层职责</strong>）。
 *
 * <pre>{@code
 * +--------+---------+--------+----------+-------------------+
 * | magic  | version | flags  | reserved | payload           |
 * | 1 byte | 1 byte  | 1 byte | 1 byte   | N bytes           |
 * +--------+---------+--------+----------+-------------------+
 *   0xC4     0x01      bit0=负副本              serializer 输出
 * }</pre>
 *
 * <p>为什么需要 envelope：Redis 的 {@code GET} 对"没有 key"与"缓存了空值"都返回 nil，
 * 必须靠 value 内容区分；同时给未来的压缩 / 加密 / 类型信息留位置。</p>
 *
 * @author Asial Jim
 */
public record Envelope(boolean negative, byte[] payload) {

    /**
     * 魔数：快速识别"这是 cache4j 写的数据"。
     */
    static final byte MAGIC = (byte) 0xC4;

    /**
     * 当前格式版本。
     */
    static final byte VERSION = 0x01;

    /**
     * 头部长度：magic + version + flags + reserved。
     */
    private static final int HEADER_LENGTH = 4;

    private static final byte FLAG_NEGATIVE = 0x01;

    /**
     * 构造真值 envelope。
     *
     * @param payload 序列化后的值
     * @return envelope
     */
    public static Envelope value(byte[] payload) {
        if (payload == null) {
            throw new CacheException(CacheResCode.CODEC_ENCODE_FAILED, "payload 不能为 null");
        }
        return new Envelope(false, payload.clone());
    }

    /**
     * 构造负副本 envelope。
     *
     * @return envelope
     */
    public static Envelope negativeValue() {
        return new Envelope(true, new byte[0]);
    }

    /**
     * 编码为 Redis 存储的字节。
     *
     * @return 字节
     */
    public byte[] encode() {
        byte[] result = new byte[HEADER_LENGTH + payload.length];
        result[0] = MAGIC;
        result[1] = VERSION;
        result[2] = negative ? FLAG_NEGATIVE : 0x00;
        result[3] = 0x00;
        System.arraycopy(payload, 0, result, HEADER_LENGTH, payload.length);
        return result;
    }

    /**
     * 解码；任何不合法（长度、magic、version）都抛 {@link CacheResCode#CODEC_DECODE_FAILED}，
     * <strong>不允许</strong>当成 miss 静默吞掉。
     *
     * @param raw Redis 中的原始字节
     * @return envelope
     */
    public static Envelope decode(byte[] raw) {
        if (raw == null || raw.length < HEADER_LENGTH) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED,
                    "envelope 长度不足：" + (raw == null ? "null" : raw.length));
        }
        if (raw[0] != MAGIC) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED,
                    "envelope magic 不匹配（可能是别的系统写入的数据）");
        }
        if (raw[1] != VERSION) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED,
                    "envelope 版本不支持：" + raw[1]);
        }
        boolean negative = (raw[2] & FLAG_NEGATIVE) != 0;
        byte[] payload = new byte[raw.length - HEADER_LENGTH];
        System.arraycopy(raw, HEADER_LENGTH, payload, 0, payload.length);
        return new Envelope(negative, payload);
    }
}
