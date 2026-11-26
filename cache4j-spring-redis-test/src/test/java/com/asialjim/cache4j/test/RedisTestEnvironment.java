package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.DynamicSpec;

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
import java.time.Duration;
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
     * @deprecated key 布局已改为"直接以 namespace 开头"，不再有全局前缀；保留常量只为兼容旧断言。
     */
    @Deprecated
    public static final String GLOBAL_PREFIX = "";

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
     * 建一个 Lettuce 连接工厂（每个测试自建自毁，互不影响anz）
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
                                                       Map<String, GlobalSpec> globalSpecs) {
        // 测试里缓存的值都是 String：Spring 的 StringRedisSerializer 即足够；
        // 生产可换成 JSON / Protobuf 等任意 RedisSerializer。
        RedisSerializer<Object> serializer = (RedisSerializer) new StringRedisSerializer();
        SpringRedisValueStore<Object> store = new SpringRedisValueStore<>(
                factory, RedisKeyFormatter.defaults(), serializer)
                .globalSpecs(globalSpecs::get);
        HotKeyCoordinator coordinator = new com.asialjim.cache4j.central.redis.RedisHotKeyCoordinator(
                store, globalSpecs::get);
        return new RedisCentralCacheProvider(store, coordinator);
    }

    /**
     * 热 key 测试用的中央阈值：窗口 1s、升热 6 QPS、冷阈 4 QPS、一个窗口低活动即降热。
     *
     * <p>QPS 口径：{@code hotCount = hotQps × 窗口秒数}，窗口 1s 时两者相等。</p>
     *
     * @return 中央阈值
     */
    public static GlobalSpec hotGlobalSpec() {
        return new GlobalSpec(
                Duration.ofSeconds(1),   // window
                6L,                      // hotQps
                4L,                      // coolQps
                1,                       // demoteWindows
                100,                     // staleWindows（本用例不走"长时间无上报"路径）
                1);                      // clientEstimate
    }

    /**
     * 热 key 测试用的 DYNAMIC 参数：本地阈值 3 QPS、窗口 1s／16 片、同步 200ms、
     * 冷却 2s（≥ demoteWindows × window）。
     *
     * @return 热度参数
     */
    public static DynamicSpec hotDynamicSpec() {
        return new DynamicSpec(
                3,                        // hotThresholdQps
                1,                        // coolQps
                Duration.ofSeconds(1),    // window
                16,                       // windowSlices
                100,                      // maxHotKeys
                Duration.ofMillis(200),   // syncInterval
                Duration.ofSeconds(2),    // cooldown
                com.asialjim.cache4j.core.Coordination.GLOBAL,
                hotGlobalSpec(),
                12,                       // sketchWidthBits
                4,                        // sketchDepth
                16,                       // hotReportPartitions
                16,                       // hotReportBatchMax
                100);                     // candidateCapacity
    }

    /**
     * 全局阈值表（测试用可变实现）。
     *
     * @return map
     */
    public static Map<String, GlobalSpec> globalSpecs() {
        return new ConcurrentHashMap<>();
    }
}
