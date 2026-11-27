package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.central.AbstractCentralCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 语义下的中央缓存骨架：把 {@link AbstractCentralCache4j} 的原语落到
 * {@link RedisClient4j} 上。
 *
 * <p>子类只需提供 {@link #client}。序列化、Lua、连接管理全在实现里，
 * 本类只负责一件事：<strong>算出 key 与 TTL，然后调用对应的语义方法</strong>。</p>
 *
 * <h2>关于 {@link Lookup} 里的版本</h2>
 * <p>{@code readRaw} 返回的 {@code Hit}/{@code Negative} 版本填 {@code 0}——
 * 不为了一个当前无人使用的字段多读一次版本。真实版本由
 * {@link AbstractRedisKeyVersionDetector4j#readVersions} 批量提供，
 * 那条路径才是版本的唯一用途。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
@RequiredArgsConstructor
public abstract class AbstractRedisCentralCache4j<V> extends AbstractCentralCache4j<V> {
    private static final Logger log = LoggerFactory.getLogger(AbstractRedisCentralCache4j.class);
    private static final RedisKeyLayout4j LAYOUT = RedisKeyLayout4j.INSTANCE;

    /**
     *  Redis 客户端
     */
    protected final RedisClient4j<V> client;

    @Override
    protected Lookup<V> readRaw(Cache4jKey key, CacheStrategy strategy) {
        Lookup<V> lookup = client.read(LAYOUT.dataKey(key));
        return lookup == null ? Lookup.miss() : lookup;
    }

    @Override
    protected void writeRaw(Cache4jKey key, V value, long ttlSeconds, CacheStrategy strategy) {
        String dataKey = LAYOUT.dataKey(key);
        String revisionKey = LAYOUT.revisionKey(key.namespace());
        String revisionField = LAYOUT.revisionField(key);
        if (value == null) {
            client.writeNegative(dataKey, ttlSeconds, revisionKey, revisionField);
        } else {
            client.write(dataKey, value, ttlSeconds, revisionKey, revisionField);
        }
    }

    @Override
    protected void deleteRaw(Cache4jKey key, CacheStrategy strategy) {
        client.delete(
                LAYOUT.dataKey(key),
                LAYOUT.revisionKey(key.namespace()),
                LAYOUT.revisionField(key));
    }

    @Override
    protected void deleteNamespace(String namespace, CacheStrategy strategy) {
        // 前缀以冒号结尾，SCAN MATCH prefix* 会命中三类 key，一把全删
        client.clearByPrefix(LAYOUT.dataKeyPrefix(namespace));
    }
}
