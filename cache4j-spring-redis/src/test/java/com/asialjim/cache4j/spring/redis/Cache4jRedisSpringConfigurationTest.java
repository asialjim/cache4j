package com.asialjim.cache4j.spring.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 值序列化器的三级优先（纯逻辑，不需要 Redis）。
 *
 * <ol>
 *   <li>容器里显式声明的 {@code RedisSerializer} Bean；</li>
 *   <li>容器里 {@link RedisTemplate} 的 {@code valueSerializer}；</li>
 *   <li>默认实现（有 jackson 用 JSON，否则 JDK）。</li>
 * </ol>
 *
 * @author Asial Jim
 */
class Cache4jRedisSpringConfigurationTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisSerializer<Object> objectSerializer(RedisSerializer<?> value) {
        return (RedisSerializer) value;
    }

    @Test
    @DisplayName("显式声明的序列化器优先级最高，压过 RedisTemplate")
    void explicitSerializerWins() {
        RedisSerializer<Object> explicit = objectSerializer(new StringRedisSerializer());
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setValueSerializer(new JdkSerializationRedisSerializer());

        assertSame(explicit, Cache4jRedisSpringConfiguration.resolveSerializer(explicit, template));
    }

    @Test
    @DisplayName("没有显式声明时沿用 RedisTemplate 的 valueSerializer")
    void templateSerializerIsUsed() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        RedisSerializer<?> expected = new StringRedisSerializer();
        template.setValueSerializer(expected);

        assertSame(expected, Cache4jRedisSpringConfiguration.resolveSerializer(null, template));
    }

    @Test
    @DisplayName("什么都没有时退回默认实现（JSON → JDK）")
    void fallsBackToDefault() {
        RedisSerializer<Object> resolved = Cache4jRedisSpringConfiguration.resolveSerializer(null, null);
        assertNotNull(resolved);
    }

    @Test
    @DisplayName("RedisTemplate 没设置 valueSerializer 时，用它的默认（JDK）")
    void templateWithoutValueSerializerUsesItsDefault() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        assertInstanceOf(JdkSerializationRedisSerializer.class,
                Cache4jRedisSpringConfiguration.resolveSerializer(null, template),
                "RedisTemplate 自己的默认就是 JDK 序列化");
    }
}
