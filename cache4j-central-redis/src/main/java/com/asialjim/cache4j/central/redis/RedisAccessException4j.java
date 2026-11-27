package com.asialjim.cache4j.central.redis;

/**
 * Redis 访问失败。
 *
 * <p>适配层统一用它包装客户端异常，避免把 Jedis / Lettuce / Spring 的异常类型漏到上层。
 * 上层只需关心"Redis 这次没成"，不必关心是哪家的客户端。</p>
 *
 * @author Asial Jim
 */
public class RedisAccessException4j extends RuntimeException {

    public RedisAccessException4j(String message) {
        super(message);
    }

    public RedisAccessException4j(String message, Throwable cause) {
        super(message, cause);
    }
}
