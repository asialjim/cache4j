package com.asialjim.cache4j.central.redis;

/**
 * 适配层抛出的统一异常。
 *
 * <p>{@link RedisValueStore} 的实现（Spring Data Redis、Redisson、Solon 插件…）必须把各自客户端的
 * 异常翻译成本异常，<strong>不得</strong>把客户端类型泄漏到语义层。{@link RedisCentralCache}
 * 再把它翻译成 {@link com.asialjim.cache4j.core.CacheException}。</p>
 *
 * @author Asial Jim
 */
public class RedisAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 故障类别。
     */
    public enum Kind {
        /**
         * 连接拒绝 / 连接断开 / 集群不可用。
         */
        UNAVAILABLE,
        /**
         * 命令超时。
         */
        TIMEOUT,
        /**
         * 其它命令错误（OOM、READONLY 等）。
         */
        COMMAND_FAILED
    }

    private final Kind kind;

    /**
     * @param kind    故障类别
     * @param message 描述
     */
    public RedisAccessException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * @param kind  故障类别
     * @param message 描述
     * @param cause 原因
     */
    public RedisAccessException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * @return 故障类别
     */
    public Kind kind() {
        return kind;
    }
}
