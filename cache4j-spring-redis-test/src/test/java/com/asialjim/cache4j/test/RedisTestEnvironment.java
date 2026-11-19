package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.central.redis.RedisCentralCacheProvider;
import com.asialjim.cache4j.spring.redis.RedisKeyFormatter;
import com.asialjim.cache4j.spring.redis.SpringRedisValueStore;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集成测试的公共装配：真实 Redis + Spring Data Redis 适配 + 二级缓存。
 *
 * <p>连接参数可用系统属性覆盖：
 * {@code -Dcache4j.test.redis.host=... -Dcache4j.test.redis.port=... -Dcache4j.test.redis.password=...}。</p>
 *
 * @author Asial Jim
 */
public final class RedisTestEnvironment {

    /**
     * Redis 主机。
     */
    public static final String HOST = System.getProperty("cache4j.test.redis.host", "127.0.0.1");

    /**
     * Redis 端口。
     */
    public static final int PORT = Integer.getInteger("cache4j.test.redis.port", 6379);

    /**
     * Redis 密码（可空）。
     */
    public static final String PASSWORD = System.getProperty("cache4j.test.redis.password", "");

    /**
     * 测试 key 前缀（避免污染业务数据）。
     */
    public static final String GLOBAL_PREFIX = "cache4j-it";

    private RedisTestEnvironment() {
    }

    /**
     * @return Redis 是否可达（不可达时整组集成用例应跳过）
     */
    public static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 建一个 Lettuce 连接工厂（每个测试自建自毁，互不影响）。
     *
     * @return 连接工厂
     */
    public static LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(HOST, PORT);
        if (!PASSWORD.isEmpty()) {
            factory.setPassword(PASSWORD);
        }
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 用给定工厂装配 Redis 中央 provider（带热 key 协调器）。
     *
     * @param factory     连接工厂
     * @param globalSpecs namespace → 中央阈值
     * @return provider
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CentralCacheProvider centralProvider(RedisConnectionFactory factory,
                                                       Map<String, LocalCacheSpec.GlobalSpec> globalSpecs) {
        // 测试里缓存的值都是 String：Spring 的 StringRedisSerializer 即足够；
        // 生产可换成 JSON / Protobuf 等任意 RedisSerializer。
        RedisSerializer<Object> serializer = (RedisSerializer) new StringRedisSerializer();
        SpringRedisValueStore<Object> store = new SpringRedisValueStore<>(
                factory, new RedisKeyFormatter(GLOBAL_PREFIX, true), serializer)
                .globalSpecs(namespace -> globalSpecs.get(namespace));
        HotKeyCoordinator coordinator = new com.asialjim.cache4j.central.redis.RedisHotKeyCoordinator(
                store, namespace -> globalSpecs.get(namespace));
        return new RedisCentralCacheProvider(store, coordinator);
    }

    /**
     * 全局阈值表（测试用可变实现）。
     *
     * @return map
     */
    public static Map<String, LocalCacheSpec.GlobalSpec> globalSpecs() {
        return new ConcurrentHashMap<>();
    }
}
