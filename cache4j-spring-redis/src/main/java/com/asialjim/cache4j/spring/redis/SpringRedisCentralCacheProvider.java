package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.central.redis.RedisCentralCache;
import com.asialjim.cache4j.central.redis.RedisHotKeyCoordinator;
import com.asialjim.cache4j.central.redis.RedisValueStore;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 开箱的 Redis 中央缓存装配：把 {@link SpringRedisValueStore}（做 I/O）与
 * {@link RedisCentralCache}（版本/负缓存/TTL 抖动/对账语义）拼起来。
 *
 * <p>不给这个 provider 的话，每个使用者都要手抄同样的 10 行；抄错（时钟、key formatter、
 * 序列化）产生的都是隐蔽 bug。</p>
 *
 * <p><strong>策略数据源不在本类的范围</strong>：那是业务存储（Redis/DB/配置中心），
 * 谁接谁实现（{@code CacheStrategyDataSource}）。本类只负责"缓存中央层"的装配。</p>
 *
 * @author Asial Jim
 */
public final class SpringRedisCentralCacheProvider implements CentralCacheProvider {

    private final RedisConnectionFactory connectionFactory;

    private final RedisKeyFormatter keyFormatter;

    private final RedisSerializer<Object> serializer;

    private final LongSupplier milliClock;

    private final Function<String, LocalCacheSpec.GlobalSpec> globalSpecs;

    /**
     * @param connectionFactory Redis 连接工厂
     * @param keyFormatter      key 布局（默认 {@link RedisKeyFormatter#defaults()}）
     * @param serializer        value 序列化（业务自行决定）
     */
    public SpringRedisCentralCacheProvider(RedisConnectionFactory connectionFactory,
                                           RedisKeyFormatter keyFormatter,
                                           RedisSerializer<Object> serializer) {
        this(connectionFactory, keyFormatter, serializer, System::currentTimeMillis, namespace -> null);
    }

    /**
     * @param connectionFactory Redis 连接工厂
     * @param keyFormatter      key 布局
     * @param serializer        value 序列化
     * @param milliClock        毫秒时钟（测试可注入）
     * @param globalSpecs       namespace → 中央热 key 阈值；返回 {@code null} 表示不启用全局协调
     */
    public SpringRedisCentralCacheProvider(RedisConnectionFactory connectionFactory,
                                           RedisKeyFormatter keyFormatter,
                                           RedisSerializer<Object> serializer,
                                           LongSupplier milliClock,
                                           Function<String, LocalCacheSpec.GlobalSpec> globalSpecs) {
        this.connectionFactory = connectionFactory;
        this.keyFormatter = keyFormatter;
        this.serializer = serializer;
        this.milliClock = milliClock;
        this.globalSpecs = globalSpecs;
    }

    @Override
    public <V> CentralCache<V> create(CacheStrategy strategy) {
        return new RedisCentralCache<>(strategy, this.<V>store());
    }

    @Override
    public Optional<HotKeyCoordinator> hotKeyCoordinator() {
        if (globalSpecs == null) {
            return Optional.empty();
        }
        return Optional.of(new RedisHotKeyCoordinator(this.<Object>store(), globalSpecs));
    }

    @Override
    public void close() {
        // 连接工厂由 Spring 管理，这里不关
    }

    @SuppressWarnings("unchecked")
    private <V> RedisValueStore<V> store() {
        // serializer 是 RedisSerializer<Object>：调用方保证它能处理实际的 V（擦除后本就同一個）
        return new SpringRedisValueStore<>(connectionFactory, keyFormatter,
                (RedisSerializer<V>) serializer, milliClock);
    }
}
