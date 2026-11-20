package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.central.CentralCacheProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 被 {@code Cache4jAutoScanConfiguration} 扫到的 Redis 装配。
 *
 * <p>放在 {@code com.asialjim.cache4j} 根包下，所以只要 {@code cache4j-spring-redis} 在 classpath
 * 上就会被注册——这就是"引了依赖就生效"的纯 Spring 版本。</p>
 *
 * <p><strong>需要容器里有 {@link RedisConnectionFactory}</strong>（Boot 的
 * {@code spring.data.redis.*} 会自动给，纯 Spring 自己声明一个）。</p>
 *
 * @author Asial Jim
 */
@Configuration(proxyBeanMethods = false)
public class Cache4jRedisSpringConfiguration {

    /**
     * 默认值序列化器（业务定义同名 {@code cache4jSerializer} 即可覆盖）。
     *
     * @return 序列化器
     */
    @Bean
    public RedisSerializer<Object> cache4jSerializer() {
        return defaultSerializer();
    }

    /**
     * @param connectionFactory Redis 连接工厂（业务提供）
     * @param serializer        值序列化器；没有则按 JSON → JDK 的顺序兜底
     * @return Redis 中央缓存 provider，Bean 名固定为 {@code cache4jCentralProvider}
     */
    @Bean("cache4jCentralProvider")
    public CentralCacheProvider cache4jCentralProvider(
            RedisConnectionFactory connectionFactory,
            ObjectProvider<RedisSerializer<Object>> serializer) {
        return new SpringRedisCentralCacheProvider(
                connectionFactory,
                RedisKeyFormatter.defaults(),
                serializer.getIfAvailable(Cache4jRedisSpringConfiguration::defaultSerializer));
    }

    /**
     * 默认值序列化器：优先 JSON，没有 jackson 时退回 JDK。
     *
     * <p>{@code spring-data-redis} 把 jackson 标成 optional，"引了 spring-data-redis"
     * 不等于"有 jackson"，所以运行时探测而不是编译期假设。</p>
     *
     * @return 序列化器
     */
    @SuppressWarnings("deprecation")
    static RedisSerializer<Object> defaultSerializer() {
        try {
            return new GenericJackson2JsonRedisSerializer();
        } catch (LinkageError ignored) {
            return new JdkSerializationRedisSerializer();
        }
    }
}
