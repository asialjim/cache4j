package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.central.AbstractCentralKeyVersionDetector4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Map;
import java.util.Set;

/**
 * Redis 语义下的版本探测：一次批量读拿到一批 key 的版本。
 *
 * <p>{@code __rev} 是"一个 namespace 一个 HASH、field 就是业务 key"的形状，
 * 所以批量读版本天然是一次 {@code HMGET}——这也是把版本表设计成 HASH 而非
 * "每条一个 key"的原因：对账要一次读几百个版本，逐条读就是几百倍往返。</p>
 *
 * <h2>缺失即已删除</h2>
 * <p>结果里没有的 key，上层（{@code AbstractVersionedLocalCache4j#doReconcile}）
 * 会解读为"中央已删除"并清掉本地副本。所以失败时<strong>绝不能返回空 Map</strong>——
 * 那等于告诉上层"这个 namespace 空了"，本地缓存会被整个清掉。
 * 实现抛 {@link RedisAccessException4j}，由上层进入退避。</p>
 *
 * @author Asial Jim
 */
public abstract class AbstractRedisKeyVersionDetector4j extends AbstractCentralKeyVersionDetector4j {

    private static final RedisKeyLayout4j LAYOUT = RedisKeyLayout4j.INSTANCE;

    /**
     * @return Redis 客户端
     */
    protected abstract RedisClient4j<?> client();

    @Override
    protected Map<String, Long> readVersions(Set<String> keys, CacheStrategy strategy) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> versions = client().readVersions(
                LAYOUT.revisionKey(strategy.namespace()), keys);
        return versions == null ? Map.of() : versions;
    }
}
