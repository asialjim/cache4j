package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 策略的<strong>持久化形态</strong>：数据源里存的就是它。
 *
 * <p>与 {@link NamespacePolicy}（解析后的完整生效值）的区别：这里的字段<strong>允许缺省</strong>
 * （{@code null} 表示"运维没设置这一项，按内建默认"），解析时必须补齐。
 * 声明值（{@code declaredStrategy}）不在持久化形态里——那是代码里的事实，不是运维配置。</p>
 *
 * @param namespace        命名空间
 * @param description      运维改写的说明；{@code null} 表示沿用声明
 * @param strategy         生效策略
 * @param maxSize          本地条目上限；{@code null} 用内建默认
 * @param localTtl         本地非空副本存活；{@code null} 用内建默认
 * @param localNullTtlDivisor 本地空值副本除子 N；{@code null} 用内建默认（10）
 * @param version          版本对账参数；{@code null} 用内建默认
 * @param dynamic          {@code DYNAMIC} 参数；{@code null} 用内建默认
 * @param centralTtl       中央非空存活；{@code null} 用内建默认
 * @param centralNullTtl   中央空值存活；{@code null} 用内建默认
 * @param failOpen         中央不可用是否降级；{@code null} 用内建默认
 * @param configVersion    配置版本号（运维乐观锁），由数据源生成
 * @param updatedAtMillis  最近一次变更时间
 * @param updatedBy        变更人（审计）
 * @param reason           变更原因（审计）
 * @author Asial Jim
 */
public record StoredPolicy(
        CacheStrategyProperty property,
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason
) {

    /**
     * 便捷构造：从展开参数转成 {@link CacheStrategyProperty} 再落入 record。
     *
     * <p>只做转换，<strong>不重复存字段</strong>——记录里唯一的配置来源仍是
     * {@link CacheStrategyProperty}。</p>
     *
     * @param namespace           命名空间
     * @param description         说明
     * @param strategy            本地策略
     * @param maxSize             本地容量
     * @param localTtl            本地 TTL
     * @param localNullTtlDivisor 本地负副本 TTL 除数
     * @param version             版本对账参数
     * @param dynamic             热 key 参数
     * @param centralTtl          中央 TTL
     * @param centralNullTtl      中央空值 TTL
     * @param failOpen            中央故障是否降级
     * @param configVersion       配置版本
     * @param updatedAtMillis     更新时间
     * @param updatedBy           更新人
     * @param reason              变更原因
     */
    public StoredPolicy(String namespace, String description, LocalStrategy strategy,
                        Long maxSize, Duration localTtl, Integer localNullTtlDivisor,
                        LocalCacheSpec.VersionSpec version, LocalCacheSpec.DynamicSpec dynamic,
                        Duration centralTtl, Duration centralNullTtl, Boolean failOpen,
                        long configVersion, long updatedAtMillis, String updatedBy, String reason) {
        this(propertyOf(namespace, description, strategy, maxSize, localTtl, localNullTtlDivisor,
                        version, dynamic, centralTtl, centralNullTtl, failOpen),
                configVersion, updatedAtMillis, updatedBy, reason);
    }

    /**
     * 逐字段填 {@link CacheStrategyProperty}，<strong>保留 {@code null}</strong>。
     *
     * <p>这一点很重要：{@code null} 表示"该配置项未设置"，序列化后仍是 {@code -}，
     * 下次读出来还是 {@code null}。不能在这里补默认值——那会把"未设置"变成"显式设为默认值"，
     * 运维就分不清哪些是改过的。</p>
     */
    private static CacheStrategyProperty propertyOf(String namespace, String description,
                                                    LocalStrategy strategy, Long maxSize,
                                                    Duration localTtl, Integer localNullTtlDivisor,
                                                    LocalCacheSpec.VersionSpec version,
                                                    LocalCacheSpec.DynamicSpec dynamic,
                                                    Duration centralTtl, Duration centralNullTtl,
                                                    Boolean failOpen) {
        CacheStrategyProperty p = new CacheStrategyProperty();
        p.setNamespace(namespace);
        p.setDescription(description);
        p.setLocalStrategy(strategy == null ? null : strategy.name());
        p.setLocalTimeUnit("MILLISECONDS");
        p.setCentralTimeUnit("MILLISECONDS");
        p.setLocalMaxSize(maxSize);
        p.setLocalTtl(localTtl == null ? null : localTtl.toMillis());
        p.setLocalNullTtlDivisor(localNullTtlDivisor);
        if (version != null) {
            p.setVersionCheckInterval(version.checkInterval().toMillis());
            p.setVersionJitter(version.jitter().toMillis());
            p.setVersionRetryBackoff(version.retryBackoff().toMillis());
            p.setReconcileBatchSize(version.reconcileBatchSize());
            p.setReconcilePartitions(version.reconcilePartitions());
        }
        if (dynamic != null) {
            p.setHotThresholdQps((long) dynamic.hotThresholdQps());
            p.setCoolQps((long) dynamic.coolQps());
            p.setHotWindow(dynamic.window().toMillis());
            p.setHotWindowSlices(dynamic.windowSlices());
            p.setMaxHotKeys(dynamic.maxHotKeys());
            p.setHotSyncInterval(dynamic.syncInterval().toMillis());
            p.setHotCooldown(dynamic.cooldown().toMillis());
            p.setCoordination(dynamic.coordination() == null ? null : dynamic.coordination().name());
            p.setSketchWidthBits(dynamic.sketchWidthBits());
            p.setSketchDepth(dynamic.sketchDepth());
            p.setHotReportPartitions(dynamic.hotReportPartitions());
            p.setHotReportBatchMax(dynamic.hotReportBatchMax());
            p.setCandidateCapacity(dynamic.candidateCapacity());
            LocalCacheSpec.GlobalSpec global = dynamic.global();
            if (global != null) {
                p.setGlobalWindow(global.window().toMillis());
                p.setGlobalHotQps(global.hotQps());
                p.setGlobalCoolQps(global.coolQps());
                p.setGlobalDemoteWindows(global.demoteWindows());
                p.setGlobalStaleWindows(global.staleWindows());
                p.setGlobalClientEstimate(global.clientEstimate());
            }
        }
        p.setCentralTtl(centralTtl == null ? null : centralTtl.toMillis());
        p.setCentralNullTtl(centralNullTtl == null ? null : centralNullTtl.toMillis());
        p.setFailOpen(failOpen);
        return p;
    }

    /**
     * @return 命名空间
     */
    public String namespace() {
        return property.getNamespace();
    }

    /**
     * @return 用途说明
     */
    public String description() {
        return property.getDescription();
    }

    /**
     * @return 本地策略
     */
    public LocalStrategy strategy() {
        return property.strategy();
    }

    /**
     * @return 本地最大条目数
     */
    public long maxSize() {
        return property.maxSize();
    }

    /**
     * @return 本地 TTL
     */
    public Duration localTtl() {
        return property.localTtlDuration();
    }

    /**
     * @return 本地负副本 TTL 除数
     */
    public int localNullTtlDivisor() {
        return property.nullTtlDivisor();
    }

    /**
     * @return 版本对账参数
     */
    public LocalCacheSpec.VersionSpec version() {
        return property.versionSpec();
    }

    /**
     * @return 热 key 参数；非 DYNAMIC 返回 {@code null}
     */
    public LocalCacheSpec.DynamicSpec dynamic() {
        return property.getLocalStrategy() != null
                && LocalStrategy.DYNAMIC.name().equals(property.getLocalStrategy())
                ? property.dynamicSpec() : null;
    }

    /**
     * @return 中央 TTL
     */
    public Duration centralTtl() {
        return property.centralTtlDuration();
    }

    /**
     * @return 中央空值 TTL
     */
    public Duration centralNullTtl() {
        return property.centralNullTtlDuration();
    }

    /**
     * @return 中央不可用时是否降级
     */
    public boolean failOpen() {
        return property.failOpenValue();
    }

}
