package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.log.CacheLogger;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(Cache4jRedisSpringConfiguration.class);

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
     * @param serializers       显式声明的值序列化器（优先级最高）
     * @param templates         容器里的 {@link RedisTemplate}（用来沿用它的 valueSerializer）
     * @return Redis 中央缓存 provider，Bean 名固定为 {@code cache4jCentralProvider}
     */
    @Bean("cache4jCentralProvider")
    public CentralCacheProvider cache4jCentralProvider(
            RedisConnectionFactory connectionFactory,
            ObjectProvider<RedisSerializer<Object>> serializers,
            ObjectProvider<RedisTemplate<?, ?>> templates) {
        return new SpringRedisCentralCacheProvider(
                connectionFactory,
                RedisKeyFormatter.defaults(),
                resolveSerializer(serializers.getIfAvailable(), templates.getIfAvailable()));
    }

    /**
     * 选值序列化器，三级优先：
     *
     * <ol>
     *   <li><strong>容器里显式的 {@code RedisSerializer} Bean</strong>——业务明确指定，听它的；</li>
     *   <li><strong>容器里 {@link RedisTemplate} 的 {@code valueSerializer}</strong>——缓存值与业务
     *       自己的 Redis 数据格式保持一致，同一个 Redis 里能用同一套工具读；</li>
     *   <li><strong>本类的默认</strong>——有 jackson 用 JSON，没有就 JDK 序列化。</li>
     * </ol>
     *
     * <p>为什么优先沿用 {@code RedisTemplate}：缓存写进的是<strong>业务自己的 Redis</strong>，
     * 用同一套序列化，排查时 {@code redis-cli} 看到的格式与业务其他数据一致；反过来（各用各的）会出现
     * "一条 Redis 里两种编码"，共用工具就得先猜是哪种。</p>
     *
     * <p>注意 {@code StringRedisTemplate} 也是 {@link RedisTemplate}，它的 valueSerializer 是
     * {@code StringRedisSerializer}——若缓存里会放非 String 值，别让它成为唯一候选
     * （显式声明一个 {@code RedisSerializer} Bean 即可覆盖）。</p>
     *
     * @param explicit 显式声明的序列化器
     * @param template 容器里的 RedisTemplate
     * @return 值序列化器
     */
    @SuppressWarnings("unchecked")
    static RedisSerializer<Object> resolveSerializer(RedisSerializer<Object> explicit,
                                                     RedisTemplate<?, ?> template) {
        if (explicit != null) {
            LOG.info(CacheLogger.Subject.ASSEMBLY, "装配",
                    "值序列化使用容器里显式声明的 RedisSerializer：" + explicit.getClass().getSimpleName());
            return explicit;
        }
        if (template != null) {
            RedisSerializer<?> fromTemplate = template.getValueSerializer();
            if (fromTemplate != null) {
                LOG.info(CacheLogger.Subject.ASSEMBLY, "装配",
                        "值序列化沿用 RedisTemplate 的 valueSerializer："
                                + fromTemplate.getClass().getSimpleName());
                return (RedisSerializer<Object>) fromTemplate;
            }
        }
        RedisSerializer<Object> fallback = defaultSerializer();
        LOG.info(CacheLogger.Subject.ASSEMBLY, "装配",
                "容器里没有可用的 RedisTemplate，值序列化使用默认实现："
                        + fallback.getClass().getSimpleName());
        return fallback;
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
