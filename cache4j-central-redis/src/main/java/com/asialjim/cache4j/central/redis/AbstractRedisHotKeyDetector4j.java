package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.central.AbstractCentralHotKeyDetector4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Redis 语义下的热 key 探测：按窗口分表的热度 HASH。
 *
 * <h2>窗口由实现用服务端时间算</h2>
 * <p>{@link RedisClient4j#accumulateHotKey} / {@link RedisClient4j#readHotKeyCounts}
 * 只接收<strong>前缀 + 窗口长度</strong>，窗口段的实际取值由实现用
 * {@code redis.call('TIME')} 算出后拼接。</p>
 *
 * <p>这样做的理由很硬：窗口只能用中央时钟划分。装完系统没同步 NTP、容器长期漂移、
 * 云主机迁移跳变——这些都会让节点算出不同的窗口，各自的 {@code HINCRBY} 落进不同的
 * HASH，合并读取时互相看不到。（时区不是问题：{@code currentTimeMillis} 是 UTC epoch，
 * 跨时区取值相同。）</p>
 *
 * <p>反过来说，<strong>不能让调用方先 {@code TIME} 再拼 key</strong>——那是两次往返，
 * 而窗口本来就该由服务端时间决定，放实现里是唯一既省往返又正确的位置。</p>
 *
 * @author Asial Jim
 */
public abstract class AbstractRedisHotKeyDetector4j extends AbstractCentralHotKeyDetector4j {

    private static final RedisKeyLayout4j LAYOUT = RedisKeyLayout4j.INSTANCE;

    /**
     * @return Redis 客户端
     */
    protected abstract RedisClient4j<?> client();

    @Override
    protected void accumulate(Map<String, Long> batch, CacheStrategy strategy) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        client().accumulateHotKey(
                LAYOUT.hotKeyPrefix(strategy.namespace()), windowSeconds(strategy), batch);
    }

    @Override
    protected Map<String, Long> readCounts(Set<String> keys, CacheStrategy strategy) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = client().readHotKeyCounts(
                LAYOUT.hotKeyPrefix(strategy.namespace()), windowSeconds(strategy), keys);
        return counts == null ? Map.of() : counts;
    }

    private static long windowSeconds(CacheStrategy strategy) {
        return Math.max(1L, Optional.ofNullable(strategy.hotWindow()).map(Supplier::get).orElse(30L));
    }
}
