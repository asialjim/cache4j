package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.KeyEncoder;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/**
 * 基于 cache4j 规范编码的 Spring {@link KeyGenerator}。
 *
 * <p>返回的 key 是字符串，形如 {@code a:1}：确定性、可复现、跨进程稳定、
 * 与 {@code @Cacheable(key = ...)} 手写的 SpEL 产出类型不同 —— 因此<strong>同一个缓存名不要混用</strong>
 * 两种风格（要么都用本生成器，要么都手写 key）。</p>
 *
 * @author Asial Jim
 */
public final class Cache4jKeyGenerator implements KeyGenerator {

    private final KeyEncoder encoder;

    /**
     * 使用默认规范编码器。
     */
    public Cache4jKeyGenerator() {
        this(KeyEncodersHolder.DEFAULT);
    }

    /**
     * @param encoder key 编码器
     */
    public Cache4jKeyGenerator(KeyEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params == null || params.length == 0) {
            // 无参方法：用方法标识兜底，避免所有无参调用挤在同一个 key 上
            return encoder.encode(target.getClass().getName() + '#' + method.getName());
        }
        return encoder.encodeAll(params);
    }

    /**
     * 默认编码器持有者（避免构造期重复创建）。
     */
    private static final class KeyEncodersHolder {
        private static final KeyEncoder DEFAULT = com.asialjim.cache4j.core.KeyEncoders.plain();
    }
}
