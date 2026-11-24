package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 一个 namespace 的<strong>全部配置</strong>的扁平载体。
 *
 * <h2>硬约束：只有包装类型与 {@link String}</h2>
 *
 * <p>字段类型不得出现 {@code Duration}、{@code List}、{@code Map} 或任何自定义对象。原因是这个对象要能被
 * <strong>配置中心 / 数据库 / 配置文件</strong>直接映射——一旦出现嵌套对象，序列化与表单渲染的复杂度
 * 就转嫁给每一个使用方。</p>
 *
 * <p>因此：</p>
 * <ul>
 *   <li>时间一律"数值 + 单位"：数值是 {@code Long}，单位由 {@link #localTimeUnit} /
 *       {@link #centralTimeUnit} 按层给出；</li>
 *   <li>枚举一律 {@code String}（与 {@code enum.name()} 对齐）；</li>
 *   <li>{@code null} 表示"未设置"，由 {@link #toLocalSpec()} / {@link #toCentralSpec()}
 *       用内建默认值补齐——配置中心只需要存改过的字段。</li>
 * </ul>
 *
 * <h2>时间单位按层给，不按字段给</h2>
 *
 * <pre>
 * localTimeUnit   → 本地 TTL、对账间隔、抖动、退避、热 key 窗口、同步间隔、冷却、中央阈值窗口
 * centralTimeUnit → 中央 TTL、中央空值 TTL
 * </pre>
 *
 * <p><strong>计数与速率字段不受单位影响</strong>：{@code hotThresholdQps} / {@code coolQps} /
 * {@code globalHotQps} / {@code globalCoolQps} 恒按 <b>QPS（每秒）</b>；{@code reconcileBatchSize} /
 * {@code maxHotKeys} / {@code hotWindowSlices} 等按"次 / 个"。</p>
 *
 * @author Asial Jim
 */
public class CacheStrategyProperty {

    // ------------------------------------------------------------------ 标识

    /**
     * 缓存命名空间，例如 {@code user:detail}。
     */
    private String namespace;

    /**
     * 用途说明，运维据此判断这个缓存是干什么用的。
     */
    private String description;

    /**
     * 本地策略：{@code NONE} / {@code STATIC} / {@code DYNAMIC}。
     */
    private String localStrategy;

    // ------------------------------------------------------------------ 时间单位

    /**
     * 本地层时间单位：{@code NANOSECONDS} / {@code MICROSECONDS} / {@code MILLISECONDS} /
     * {@code SECONDS} / {@code MINUTES} / {@code HOURS} / {@code DAYS}。默认 {@code SECONDS}。
     */
    private String localTimeUnit;

    /**
     * 中央层时间单位，默认 {@code MINUTES}。
     */
    private String centralTimeUnit;

    // ------------------------------------------------------------------ 本地：容量与 TTL

    /**
     * 本地最大条目数；单位=个。
     */
    private Long localMaxSize;

    /**
     * 本地条目存活时长；单位={@link #localTimeUnit}。
     */
    private Long localTtl;

    /**
     * 本地负副本 TTL 的除数：本地空值 TTL = {@code centralNullTtl / 该值}。
     */
    private Integer localNullTtlDivisor;

    // ------------------------------------------------------------------ 本地：版本对账

    /**
     * 对账间隔；单位={@link #localTimeUnit}。
     */
    private Long versionCheckInterval;

    /**
     * 对账抖动（绝对时长）；单位={@link #localTimeUnit}。
     */
    private Long versionJitter;

    /**
     * 数据源不可用时的重试退避；单位={@link #localTimeUnit}。
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

    // ------------------------------------------------------------------ 本地：热 key 判定（仅 DYNAMIC）

    /**
     * 本地升热阈值；<strong>QPS，恒按每秒</strong>，不受时间单位影响。
     */
    private Long hotThresholdQps;

    /**
     * 本地降热阈值；<strong>QPS</strong>。
     */
    private Long coolQps;

    /**
     * 频次统计窗口；单位={@link #localTimeUnit}。
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
     * 热 key 上报间隔；单位={@link #localTimeUnit}。
     */
    private Long hotSyncInterval;

    /**
     * 降热后的冷却时长，期间拒绝重新升热；单位={@link #localTimeUnit}。
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
     * 中央窗口长度；单位={@link #localTimeUnit}。
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

    // ------------------------------------------------------------------ 中央

    /**
     * 中央值存活时长；单位={@link #centralTimeUnit}。
     */
    private Long centralTtl;

    /**
     * 中央负副本存活时长；单位={@link #centralTimeUnit}。
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

    // ------------------------------------------------------------------ 转换

    /**
     * 还原为本地规格；{@code null} 字段用内建默认值补齐。
     *
     * @return 本地规格
     */
    public LocalCacheSpec toLocalSpec() {
        LocalStrategy strategy = LocalStrategy.valueOf(
                localStrategy == null ? LocalStrategy.STATIC.name() : localStrategy);
        if (strategy == LocalStrategy.NONE) {
            return LocalCacheSpec.noLocal(namespace);
        }
        Duration ttl = localTtl == null
                ? BuiltinDefaults.LOCAL_TTL
                : duration(localTtl, localTimeUnit, TimeUnit.SECONDS);
        long maxSize = localMaxSize == null ? BuiltinDefaults.MAX_SIZE : localMaxSize;
        LocalCacheSpec.VersionSpec version = new LocalCacheSpec.VersionSpec(
                duration(versionCheckInterval, localTimeUnit, TimeUnit.SECONDS),
                duration(versionJitter, localTimeUnit, TimeUnit.MILLISECONDS),
                duration(versionRetryBackoff, localTimeUnit, TimeUnit.MILLISECONDS),
                reconcileBatchSize == null ? 1_000 : reconcileBatchSize,
                reconcilePartitions == null ? LocalCacheSpec.VersionSpec.DEFAULT_PARTITIONS : reconcilePartitions);
        if (strategy == LocalStrategy.STATIC) {
            return new LocalCacheSpec(namespace, strategy, maxSize, ttl, version, null);
        }
        LocalCacheSpec.DynamicSpec dynamic = new LocalCacheSpec.DynamicSpec(
                intOf(hotThresholdQps, 100), intOf(coolQps, 25),
                duration(hotWindow, localTimeUnit, TimeUnit.SECONDS),
                intOf(hotWindowSlices, 16), intOf(maxHotKeys, 1_000),
                duration(hotSyncInterval, localTimeUnit, TimeUnit.MILLISECONDS),
                duration(hotCooldown, localTimeUnit, TimeUnit.SECONDS),
                LocalCacheSpec.Coordination.valueOf(coordination == null ? "LOCAL" : coordination),
                globalSpec(),
                intOf(sketchWidthBits, 12), intOf(sketchDepth, 4),
                intOf(hotReportPartitions, 16), intOf(hotReportBatchMax, 16),
                intOf(candidateCapacity, 1_000));
        return new LocalCacheSpec(namespace, strategy, maxSize, ttl, version, dynamic);
    }

    /**
     * 还原为中央规格；{@code null} 字段用内建默认值补齐。
     *
     * @return 中央规格
     */
    public CentralCacheSpec toCentralSpec() {
        return new CentralCacheSpec(
                namespace,
                centralTtl == null ? BuiltinDefaults.CENTRAL_TTL
                        : duration(centralTtl, centralTimeUnit, TimeUnit.MINUTES),
                centralNullTtl == null ? BuiltinDefaults.CENTRAL_NULL_TTL
                        : duration(centralNullTtl, centralTimeUnit, TimeUnit.SECONDS),
                failOpen == null || failOpen,
                centralTtlJitter == null ? 0D : centralTtlJitter,
                allowNoExpiry != null && allowNoExpiry);
    }

    private LocalCacheSpec.GlobalSpec globalSpec() {
        if (globalWindow == null && globalHotQps == null) {
            return null;
        }
        return new LocalCacheSpec.GlobalSpec(
                duration(globalWindow, localTimeUnit, TimeUnit.SECONDS),
                globalHotQps == null ? 100L : globalHotQps,
                globalCoolQps == null ? 25L : globalCoolQps,
                intOf(globalDemoteWindows, 1),
                intOf(globalStaleWindows, 6),
                intOf(globalClientEstimate, 1));
    }

    private static Duration duration(Long value, String unit, TimeUnit fallback) {
        TimeUnit resolved = unit == null ? fallback : TimeUnit.valueOf(unit);
        return Duration.of(value == null ? 0L : value, resolved.toChronoUnit());
    }

    private static int intOf(Long value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    private static int intOf(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    // ------------------------------------------------------------------ getter / setter

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocalStrategy() {
        return localStrategy;
    }

    public void setLocalStrategy(String localStrategy) {
        this.localStrategy = localStrategy;
    }

    public String getLocalTimeUnit() {
        return localTimeUnit;
    }

    public void setLocalTimeUnit(String localTimeUnit) {
        this.localTimeUnit = localTimeUnit;
    }

    public String getCentralTimeUnit() {
        return centralTimeUnit;
    }

    public void setCentralTimeUnit(String centralTimeUnit) {
        this.centralTimeUnit = centralTimeUnit;
    }

    public Long getLocalMaxSize() {
        return localMaxSize;
    }

    public void setLocalMaxSize(Long localMaxSize) {
        this.localMaxSize = localMaxSize;
    }

    public Long getLocalTtl() {
        return localTtl;
    }

    public void setLocalTtl(Long localTtl) {
        this.localTtl = localTtl;
    }

    public Integer getLocalNullTtlDivisor() {
        return localNullTtlDivisor;
    }

    public void setLocalNullTtlDivisor(Integer localNullTtlDivisor) {
        this.localNullTtlDivisor = localNullTtlDivisor;
    }

    public Long getVersionCheckInterval() {
        return versionCheckInterval;
    }

    public void setVersionCheckInterval(Long versionCheckInterval) {
        this.versionCheckInterval = versionCheckInterval;
    }

    public Long getVersionJitter() {
        return versionJitter;
    }

    public void setVersionJitter(Long versionJitter) {
        this.versionJitter = versionJitter;
    }

    public Long getVersionRetryBackoff() {
        return versionRetryBackoff;
    }

    public void setVersionRetryBackoff(Long versionRetryBackoff) {
        this.versionRetryBackoff = versionRetryBackoff;
    }

    public Integer getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(Integer reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public Integer getReconcilePartitions() {
        return reconcilePartitions;
    }

    public void setReconcilePartitions(Integer reconcilePartitions) {
        this.reconcilePartitions = reconcilePartitions;
    }

    public Long getHotThresholdQps() {
        return hotThresholdQps;
    }

    public void setHotThresholdQps(Long hotThresholdQps) {
        this.hotThresholdQps = hotThresholdQps;
    }

    public Long getCoolQps() {
        return coolQps;
    }

    public void setCoolQps(Long coolQps) {
        this.coolQps = coolQps;
    }

    public Long getHotWindow() {
        return hotWindow;
    }

    public void setHotWindow(Long hotWindow) {
        this.hotWindow = hotWindow;
    }

    public Integer getHotWindowSlices() {
        return hotWindowSlices;
    }

    public void setHotWindowSlices(Integer hotWindowSlices) {
        this.hotWindowSlices = hotWindowSlices;
    }

    public Integer getMaxHotKeys() {
        return maxHotKeys;
    }

    public void setMaxHotKeys(Integer maxHotKeys) {
        this.maxHotKeys = maxHotKeys;
    }

    public Long getHotSyncInterval() {
        return hotSyncInterval;
    }

    public void setHotSyncInterval(Long hotSyncInterval) {
        this.hotSyncInterval = hotSyncInterval;
    }

    public Long getHotCooldown() {
        return hotCooldown;
    }

    public void setHotCooldown(Long hotCooldown) {
        this.hotCooldown = hotCooldown;
    }

    public String getCoordination() {
        return coordination;
    }

    public void setCoordination(String coordination) {
        this.coordination = coordination;
    }

    public Integer getSketchWidthBits() {
        return sketchWidthBits;
    }

    public void setSketchWidthBits(Integer sketchWidthBits) {
        this.sketchWidthBits = sketchWidthBits;
    }

    public Integer getSketchDepth() {
        return sketchDepth;
    }

    public void setSketchDepth(Integer sketchDepth) {
        this.sketchDepth = sketchDepth;
    }

    public Integer getHotReportPartitions() {
        return hotReportPartitions;
    }

    public void setHotReportPartitions(Integer hotReportPartitions) {
        this.hotReportPartitions = hotReportPartitions;
    }

    public Integer getHotReportBatchMax() {
        return hotReportBatchMax;
    }

    public void setHotReportBatchMax(Integer hotReportBatchMax) {
        this.hotReportBatchMax = hotReportBatchMax;
    }

    public Integer getCandidateCapacity() {
        return candidateCapacity;
    }

    public void setCandidateCapacity(Integer candidateCapacity) {
        this.candidateCapacity = candidateCapacity;
    }

    public Long getGlobalWindow() {
        return globalWindow;
    }

    public void setGlobalWindow(Long globalWindow) {
        this.globalWindow = globalWindow;
    }

    public Long getGlobalHotQps() {
        return globalHotQps;
    }

    public void setGlobalHotQps(Long globalHotQps) {
        this.globalHotQps = globalHotQps;
    }

    public Long getGlobalCoolQps() {
        return globalCoolQps;
    }

    public void setGlobalCoolQps(Long globalCoolQps) {
        this.globalCoolQps = globalCoolQps;
    }

    public Integer getGlobalDemoteWindows() {
        return globalDemoteWindows;
    }

    public void setGlobalDemoteWindows(Integer globalDemoteWindows) {
        this.globalDemoteWindows = globalDemoteWindows;
    }

    public Integer getGlobalStaleWindows() {
        return globalStaleWindows;
    }

    public void setGlobalStaleWindows(Integer globalStaleWindows) {
        this.globalStaleWindows = globalStaleWindows;
    }

    public Integer getGlobalClientEstimate() {
        return globalClientEstimate;
    }

    public void setGlobalClientEstimate(Integer globalClientEstimate) {
        this.globalClientEstimate = globalClientEstimate;
    }

    public Long getCentralTtl() {
        return centralTtl;
    }

    public void setCentralTtl(Long centralTtl) {
        this.centralTtl = centralTtl;
    }

    public Long getCentralNullTtl() {
        return centralNullTtl;
    }

    public void setCentralNullTtl(Long centralNullTtl) {
        this.centralNullTtl = centralNullTtl;
    }

    public Double getCentralTtlJitter() {
        return centralTtlJitter;
    }

    public void setCentralTtlJitter(Double centralTtlJitter) {
        this.centralTtlJitter = centralTtlJitter;
    }

    public Boolean getFailOpen() {
        return failOpen;
    }

    public void setFailOpen(Boolean failOpen) {
        this.failOpen = failOpen;
    }

    public Boolean getAllowNoExpiry() {
        return allowNoExpiry;
    }

    public void setAllowNoExpiry(Boolean allowNoExpiry) {
        this.allowNoExpiry = allowNoExpiry;
    }
}
