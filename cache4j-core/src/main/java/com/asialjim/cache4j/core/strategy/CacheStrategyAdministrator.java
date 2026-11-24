package com.asialjim.cache4j.core.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 策略的<strong>管理面</strong>：分页、详情、变更。
 *
 * <p>与 {@link CacheStrategyCache} 的分工：</p>
 * <ul>
 *   <li>读 —— 走 {@link CacheStrategyCache}（命中自举缓存，不打数据源）；</li>
 *   <li>管理 —— 走这里：查询直连数据源（要的是权威列表，不是缓存），变更直连数据源并由
 *       本类负责把结果同步回自举缓存。</li>
 * </ul>
 *
 * <p>两者共享<strong>同一个</strong> {@link DefaultCacheStrategyRepository}，否则变更后的快照
 * 与读路径看到的不是一回事。</p>
 *
 * @author Asial Jim
 */
public final class CacheStrategyAdministrator implements CacheStrategyAdmin {

    private static final Logger LOG = LoggerFactory.getLogger(CacheStrategyAdministrator.class);

    private final DefaultCacheStrategyRepository repository;

    private final CacheStrategyCache readCache;

    /**
     * @param readCache 读路径（共享其底层仓库，避免两套快照）
     */
    public CacheStrategyAdministrator(CacheStrategyCache readCache) {
        this.readCache = readCache;
        this.repository = readCache.repository();
    }

    @Override
    public Page<StrategyView> page(String keyword, int page, int size) {
        return repository.page(keyword, page, size);
    }

    @Override
    public Optional<StrategyView> view(String namespace) {
        return repository.view(namespace);
    }

    @Override
    public ChangeResult change(StrategyChange change) {
        ChangeResult result = repository.change(change);
        if (!result.applied() || result.current() == null) {
            return result;
        }
        return writeThrough(change, result);
    }

    /**
     * 写穿：数据源已成功，把新策略同步进自举缓存。
     *
     * <p>三级降级：写穿 → 失败则 evict（让下次读回源，仍需 ≤5s 完成传播）→ 都失败才是
     * {@code APPLIED_STALE}（本机已生效，其他节点最坏要等中央 TTL）。</p>
     *
     * @param change 请求
     * @param result 委托层结果
     * @return 最终结果
     */
    private ChangeResult writeThrough(StrategyChange change, ChangeResult result) {
        String namespace = change.namespace();
        try {
            // 无条件覆盖：写穿的是刚从数据源拿到的权威值，必然最新
            readCache.putPolicy(namespace, CacheStrategyCache.toStored(result.current()));
            return result;
        } catch (RuntimeException writeError) {
            LOG.warn("[{}]-{}-{}", "策略仓库", "写穿", "写穿失败，退化为 evict 兜底：namespace=" + namespace
                    + "，原因=" + writeError.getMessage());
        }
        try {
            readCache.evictPolicy(namespace);
            return new ChangeResult(ChangeResult.Outcome.APPLIED, namespace,
                    result.configVersion(), result.current(),
                    result.message() + "（写穿失败，已清自举缓存，下次读回源）", result.requestId());
        } catch (RuntimeException evictError) {
            LOG.error("[{}]-{}-{}", "策略仓库", "写穿", "写穿与 evict 都失败：namespace=" + namespace
                    + "，其他节点可能延迟到中央 TTL 才生效，原因=" + evictError.getMessage(), evictError);
            return new ChangeResult(ChangeResult.Outcome.APPLIED_STALE, namespace,
                    result.configVersion(), result.current(),
                    "已持久化，但自举缓存同步失败（写穿与 evict 均失败），需告警并补偿", result.requestId());
        }
    }
}
