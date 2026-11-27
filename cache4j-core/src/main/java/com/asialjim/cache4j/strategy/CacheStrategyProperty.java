package com.asialjim.cache4j.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStrategyProperty implements Serializable {
    public static final CacheStrategy noneLocalCacheStrategy = noneCacheStrategy().cacheStrategy();
    public static final CacheStrategy selfCacheStrategy = selfCacheStrategy().cacheStrategy();
    // --- 缓存生命 ---
    /**
     * 缓存命名空间，例如 {@code user:detail}。
     */
    private String namespace;

    /**
     * 用途说明，运维据此判断这个缓存是干什么用的。
     */
    private String description;
    private Integer version;

    /**
     * 本地策略：{@code NONE} / {@code STATIC} / {@code DYNAMIC}。
     */
    private String localStrategy;
    // --- 缓存生命 ---


    // --- 本地容量与TTL

    /**
     * 本地最大条目数；单位=个。
     */
    private Long localMaxSize;

    /**
     * 本地条目存活时长；单位=<strong>秒</strong>。
     */
    private Long localTtl;

    /**
     * 本地负副本 TTL 的倍率：本地空值 TTL = {@code centralNullTtl / 该值}。
     */
    private Integer localNullTtlDivisor;


    // --- 本地：版本对账

    /**
     * 对账间隔；单位=<strong>秒</strong>。
     */
    private Long versionCheckInterval;

    /**
     * 对账抖动（绝对时长）；单位=<strong>秒</strong>。
     */
    private Long versionJitter;

    /**
     * 数据源不可用时的重试退避；单位=<strong>秒</strong>。
     */
    private Long versionRetryBackoff;

    /**
     * 一次对账的批量大小；单位=个。
     */
    private Integer reconcileBatchSize;

    /**
     * 对账分区数；单位=个。
     */
    private Integer reconcilePartitions;


    // --- 本地：热 key 判定（仅 DYNAMIC）

    /**
     * 本地升热阈值；<strong>QPS，恒按每秒</strong>，不受时间单位影响。
     */
    private Long hotThresholdQps;

    /**
     * 本地降热阈值；<strong>QPS</strong>。
     */
    private Long coolQps;

    /**
     * 频次统计窗口；单位=<strong>秒</strong>。
     */
    private Long hotWindow;

    /**
     * 窗口切片数；单位=片。
     */
    private Integer hotWindowSlices;

    /**
     * 本地最多容纳的热点 key 数；单位=个。
     */
    private Integer maxHotKeys;

    /**
     * 热 key 上报间隔；单位=<strong>秒</strong>。
     */
    private Long hotSyncInterval;

    /**
     * 降热后的冷却时长，期间拒绝重新升热；单位=<strong>秒</strong>。
     */
    private Long hotCooldown;

    /**
     * 协调模式：{@code LOCAL} / {@code GLOBAL}。
     */
    private String coordination;

    /**
     * 频率草图宽度位数；单位=位。
     */
    private Integer sketchWidthBits;

    /**
     * 频率草图深度；单位=行。
     */
    private Integer sketchDepth;

    /**
     * 热 key 上报分区数；单位=个。
     */
    private Integer hotReportPartitions;

    /**
     * 单次上报批量上限；单位=个。
     */
    private Integer hotReportBatchMax;

    /**
     * 候选集合容量；单位=个。
     */
    private Integer candidateCapacity;


    // ------------------------------------------------------------------ 本地：中央侧阈值（仅 GLOBAL 协调）

    /**
     * 中央窗口长度；单位=<strong>秒</strong>。
     */
    private Long globalWindow;

    /**
     * 中央升热阈值；<strong>QPS</strong>。
     */
    private Long globalHotQps;

    /**
     * 中央降热阈值；<strong>QPS</strong>。
     */
    private Long globalCoolQps;

    /**
     * 连续多少个窗口低于冷却阈值才降热；单位=窗口。
     */
    private Integer globalDemoteWindows;

    /**
     * 多少个窗口无上报视为失联；单位=窗口。
     */
    private Integer globalStaleWindows;

    /**
     * 中央估算的客户端数量；单位=个。
     */
    private Integer globalClientEstimate;

    // --- 中央

    /**
     * 中央值存活时长；单位=<strong>秒</strong>。
     */
    private Long centralTtl;

    /**
     * 中央负副本存活时长；单位=<strong>秒</strong>。
     */
    private Long centralNullTtl;

    /**
     * 中央 TTL 抖动比例（0 ~ 0.5）。
     */
    private Double centralTtlJitter;

    /**
     * 中央不可用时是否降级放行（读按未命中、写按本地成功）。
     */
    private Boolean failOpen;

    /**
     * 是否允许无过期（{@code ttl} 为 0 表示永不过期）。
     */
    private Boolean allowNoExpiry;

    public CacheStrategy cacheStrategy() {
        return new CacheStrategy(
                getNamespace(),
                this::getDescription,
                this::getVersion,
                () -> LocalStrategy.valueOf(getLocalStrategy()),
                () -> Optional.ofNullable(this.centralNullTtl).map(i -> i> 0).orElse(false),
                this::getLocalMaxSize,
                this::getLocalTtl,
                this::getLocalNullTtlDivisor,
                this::getVersionCheckInterval,
                this::getVersionJitter,
                this::getVersionRetryBackoff,
                this::getReconcileBatchSize,
                this::getReconcilePartitions,
                this::getHotThresholdQps,
                this::getCoolQps,
                this::getHotWindow,
                this::getHotWindowSlices,
                this::getMaxHotKeys,
                this::getHotSyncInterval,
                this::getHotCooldown,
                this::getCoordination,
                this::getSketchWidthBits,
                this::getSketchDepth,
                this::getHotReportPartitions,
                this::getHotReportBatchMax,
                this::getCandidateCapacity,
                this::getGlobalWindow,
                this::getGlobalHotQps,
                this::getGlobalCoolQps,
                this::getGlobalDemoteWindows,
                this::getGlobalStaleWindows,
                this::getGlobalClientEstimate,
                this::getCentralTtl,
                this::getCentralNullTtl,
                this::getCentralTtlJitter,
                this::getFailOpen,
                this::getAllowNoExpiry
        );
    }

    private static CacheStrategyProperty selfCacheStrategy() {
        CacheStrategyProperty object = new CacheStrategyProperty();
        object.setNamespace("_stg_");
        object.setDescription("缓存策略缓存块-自举缓存");
        object.setVersion(0);
        object.setLocalStrategy(LocalStrategy.STATIC.name());
        object.setLocalMaxSize(10_000L);
        object.setLocalTtl(5 * 60L);
        object.setLocalNullTtlDivisor(10);
        object.setVersionCheckInterval(10L);
        object.setVersionJitter(1L);
        object.setVersionRetryBackoff(1L);
        object.setReconcileBatchSize(1 << 6);
        object.setReconcilePartitions(1 << 4);
        object.setHotThresholdQps(null);
        object.setCoolQps(null);
        object.setHotWindow(null);
        object.setHotWindowSlices(null);
        object.setMaxHotKeys(null);
        object.setHotSyncInterval(null);
        object.setHotCooldown(null);
        object.setCoordination(null);
        object.setSketchWidthBits(null);
        object.setSketchDepth(null);
        object.setHotReportPartitions(null);
        object.setHotReportBatchMax(null);
        object.setCandidateCapacity(null);
        object.setGlobalWindow(null);
        object.setGlobalHotQps(null);
        object.setGlobalCoolQps(null);
        object.setGlobalDemoteWindows(null);
        object.setGlobalStaleWindows(null);
        object.setGlobalClientEstimate(null);

        // --- 中央
        // 单位统一为秒：原 5 小时 → 18000 秒；原 1 小时 → 3600 秒
        object.setCentralTtl(5L * 60 * 60);
        object.setCentralNullTtl(60L * 60);
        object.setCentralTtlJitter(0.01);
        object.setFailOpen(true);
        object.setAllowNoExpiry(null);
        return object;
    }


    private static CacheStrategyProperty noneCacheStrategy() {
        CacheStrategyProperty object = new CacheStrategyProperty();
        object.setNamespace("_NONE_LOCAL_STRATEGY_");
        object.setDescription("NONE本地策略");
        object.setVersion(0);
        object.setLocalStrategy(LocalStrategy.NONE.name());
        object.setLocalMaxSize(10_000L);
        object.setLocalTtl(5 * 60L);
        object.setLocalNullTtlDivisor(10);
        object.setVersionCheckInterval(10L);
        object.setVersionJitter(1L);
        object.setVersionRetryBackoff(1L);
        object.setReconcileBatchSize(1 << 6);
        object.setReconcilePartitions(1 << 4);
        object.setHotThresholdQps(null);
        object.setCoolQps(null);
        object.setHotWindow(null);
        object.setHotWindowSlices(null);
        object.setMaxHotKeys(null);
        object.setHotSyncInterval(null);
        object.setHotCooldown(null);
        object.setCoordination(null);
        object.setSketchWidthBits(null);
        object.setSketchDepth(null);
        object.setHotReportPartitions(null);
        object.setHotReportBatchMax(null);
        object.setCandidateCapacity(null);
        object.setGlobalWindow(null);
        object.setGlobalHotQps(null);
        object.setGlobalCoolQps(null);
        object.setGlobalDemoteWindows(null);
        object.setGlobalStaleWindows(null);
        object.setGlobalClientEstimate(null);

        // --- 中央
        // 单位统一为秒：原 5 小时 → 18000 秒；原 1 小时 → 3600 秒
        object.setCentralTtl(5L * 60 * 60);
        object.setCentralNullTtl(60L * 60);
        object.setCentralTtlJitter(0.01);
        object.setFailOpen(true);
        object.setAllowNoExpiry(null);
        return object;
    }
}
