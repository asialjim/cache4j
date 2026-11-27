package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.KeyEncoder;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 基于 {@link KeyEncoder} 的 Spring {@link KeyGenerator}。
 *
 * <p>产出的是<strong>字符串 key</strong>：确定性、可复现、跨进程稳定。与
 * {@code @Cacheable(key = "#id")} 手写的 SpEL 结果类型不同，因此
 * <strong>同一个缓存名不要混用</strong>两种风格——要么都用本生成器，要么都手写 key。</p>
 *
 * <p>无参方法用"类名#方法名"兜底：否则所有无参调用会挤在同一个 key 上，
 * 彼此覆盖。</p>
 *
 * @author Asial Jim
 */
@Component
@SuppressWarnings("NullableProblems")
public final class Cache4jKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        //noinspection ConstantValue
        if (Objects.isNull(params))
            return "_nil_";
        if (params.length == 0)
            return "[]";
        if (params.length == 1)
            return String.valueOf(params[0]);
        StringJoiner sj = new StringJoiner(",");
        for (Object param : params) {
            sj.add(String.valueOf(param));
        }
        String string = sj.toString();
        if (string.length() > 40)
            return "MD5-" + md5(string);
        return string;
    }


    private String md5(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "_err_";
        }
    }
}
