package com.asialjim.cache4j.core;

import com.asialjim.standard4j.exception.ResCode;

/**
 * 缓存错误码（{@code CACHE-} 前缀，遵循 standard4j 的"模块前缀 + 数字"规范）。
 *
 * <p>只收录缓存基础设施故障；loader 内部的业务异常保持原类型，不被包装成缓存异常。</p>
 *
 * @author Asial Jim
 */
public enum CacheResCode implements ResCode {

    /**
     * 未知缓存错误。
     */
    UNKNOWN("CACHE-00000", "未知缓存错误", 500),

    /**
     * 配置非法：TTL 为负、STATIC 未设容量、DYNAMIC 阈值越界等。
     */
    INVALID_SPEC("CACHE-00001", "缓存配置非法", 500),

    /**
     * key 类型不支持、自定义编码器抛错。
     */
    KEY_ENCODE_FAILED("CACHE-00002", "缓存 key 编码失败", 500),

    /**
     * 值序列化失败。
     */
    CODEC_ENCODE_FAILED("CACHE-00003", "缓存值序列化失败", 500),

    /**
     * 值反序列化失败、envelope 损坏。
     */
    CODEC_DECODE_FAILED("CACHE-00004", "缓存值反序列化失败", 500),

    /**
     * 中央缓存连接失败、命令失败且未降级。
     */
    CENTRAL_UNAVAILABLE("CACHE-00005", "中央缓存不可用", 503),

    /**
     * 中央缓存命令超时。
     */
    CENTRAL_TIMEOUT("CACHE-00006", "中央缓存超时", 504),

    /**
     * 本地缓存初始化失败。
     */
    LOCAL_INIT_FAILED("CACHE-00007", "本地缓存初始化失败", 500),
    ;

    private final String code;
    private final String label;
    private final int status;

    CacheResCode(String code, String label, int status) {
        this.code = code;
        this.label = label;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int status() {
        return status;
    }
}
