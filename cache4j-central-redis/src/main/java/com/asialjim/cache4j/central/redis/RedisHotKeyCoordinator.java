package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.log.CacheLogger;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.Map;
import java.util.function.Function;

/**
 * 热 key 协调器（Redis 版）：只做<strong>阈值解析与状态机</strong>。
 *
 * <p>窗口计数怎么存（`ZSET` / `HASH` / pipeline / Lua）全由 {@link RedisValueStore#reportHotKeyAll}
 * 的实现决定；本类不碰字节，也不拼 key。</p>
 *
 * @author Asial Jim
 */
public final class RedisHotKeyCoordinator implements HotKeyCoordinator {

    private final RedisValueStore<Object> store;
    private static final CacheLogger LOG = CacheLogger.get(RedisHotKeyCoordinator.class);

    private final Function<String, LocalCacheSpec.GlobalSpec> globalSpecs;

    /**
     * @param store       下行存储（适配层实现）
     * @param globalSpecs namespace → 中央阈值；未配置的 namespace 返回 {@code null}
     */
    public RedisHotKeyCoordinator(RedisValueStore<Object> store,
                                  Function<String, LocalCacheSpec.GlobalSpec> globalSpecs) {
        this.store = store;
        this.globalSpecs = globalSpecs;
    }

    @Override
    public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
        LocalCacheSpec.GlobalSpec globalSpec = globalSpecs == null ? null : globalSpecs.apply(namespace);
        if (globalSpec == null) {
            // 不猜阈值：该 namespace 没配 dynamic.global
            LOG.warnThrottled(CacheLogger.GLOBAL_SUBJECT, "热key", () -> "namespace 未配置 dynamic.global，"
                    + "全局热 key 判定被跳过：namespace=" + namespace);
            return HotKeyReport.unknown();
        }
        return store.reportHotKey(namespace, key, delta);
    }

    @Override
    public Map<CacheKey, HotKeyReport> reportAndStatusAll(String namespace, Map<CacheKey, Long> deltas) {
        LocalCacheSpec.GlobalSpec globalSpec = globalSpecs == null ? null : globalSpecs.apply(namespace);
        if (globalSpec == null) {
            return deltas.keySet().stream()
                    .collect(java.util.stream.Collectors.toMap(key -> key, key -> HotKeyReport.unknown()));
        }
        return store.reportHotKeyAll(namespace, deltas);
    }

    @Override
    public void close() {
        // store 的生命周期由 provider 拥有
    }
}
