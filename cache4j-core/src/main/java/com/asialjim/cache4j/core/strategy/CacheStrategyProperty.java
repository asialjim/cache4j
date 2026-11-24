package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.Objects;
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

    /**
     * 从展开参数构造，<strong>保留 {@code null}</strong>（表示未设置）。
     *
     * <p>与 {@link #of(NamespacePolicy)} 的区别：那个填的是"已解析的生效值"（默认值已补齐），
     * 这个填的是"记录里原样的值"——{@code null} 必须保持 {@code null}，序列化后仍是 {@code -}。</p>
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
     * @return 配置载体
     */
    public static CacheStrategyProperty ofRaw(String namespace, String description,
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


    // ------------------------------------------------------------------ 转换

    /**
     * 从解析后的生效值还原配置（持久化、写穿时用）。
     *
     * <p>时间统一按毫秒表示，保证往返精确。</p>
     *
     * @param policy 生效值
     * @return 配置载体
     */
    public static CacheStrategyProperty of(NamespacePolicy policy) {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace(policy.namespace());
        property.setDescription(policy.description());
        property.setLocalStrategy(policy.strategy() == null ? null : policy.strategy().name());
        property.setLocalTimeUnit("MILLISECONDS");
        property.setCentralTimeUnit("MILLISECONDS");
        property.setLocalMaxSize(policy.maxSize());
        property.setLocalTtl(policy.localTtl() == null ? null : policy.localTtl().toMillis());
        property.setLocalNullTtlDivisor(policy.localNullTtlDivisor());

        LocalCacheSpec.VersionSpec version = policy.version();
        if (version != null) {
            property.setVersionCheckInterval(version.checkInterval().toMillis());
            property.setVersionJitter(version.jitter().toMillis());
            property.setVersionRetryBackoff(version.retryBackoff().toMillis());
            property.setReconcileBatchSize(version.reconcileBatchSize());
            property.setReconcilePartitions(version.reconcilePartitions());
        }

        LocalCacheSpec.DynamicSpec dynamic = policy.dynamic();
        if (dynamic != null) {
            property.setHotThresholdQps((long) dynamic.hotThresholdQps());
            property.setCoolQps((long) dynamic.coolQps());
            property.setHotWindow(dynamic.window().toMillis());
            property.setHotWindowSlices(dynamic.windowSlices());
            property.setMaxHotKeys(dynamic.maxHotKeys());
            property.setHotSyncInterval(dynamic.syncInterval().toMillis());
            property.setHotCooldown(dynamic.cooldown().toMillis());
            property.setCoordination(dynamic.coordination() == null ? null : dynamic.coordination().name());
            property.setSketchWidthBits(dynamic.sketchWidthBits());
            property.setSketchDepth(dynamic.sketchDepth());
            property.setHotReportPartitions(dynamic.hotReportPartitions());
            property.setHotReportBatchMax(dynamic.hotReportBatchMax());
            property.setCandidateCapacity(dynamic.candidateCapacity());
            LocalCacheSpec.GlobalSpec global = dynamic.global();
            if (global != null) {
                property.setGlobalWindow(global.window().toMillis());
                property.setGlobalHotQps(global.hotQps());
                property.setGlobalCoolQps(global.coolQps());
                property.setGlobalDemoteWindows(global.demoteWindows());
                property.setGlobalStaleWindows(global.staleWindows());
                property.setGlobalClientEstimate(global.clientEstimate());
            }
        }

        property.setCentralTtl(policy.centralTtl() == null ? null : policy.centralTtl().toMillis());
        property.setCentralNullTtl(policy.centralNullTtl() == null ? null : policy.centralNullTtl().toMillis());
        property.setFailOpen(policy.failOpen());
        return property;
    }

    /**
     * @return 本地策略
     * @throws com.asialjim.cache4j.core.CacheException 未设置时抛出——"缺策略"是损坏记录，
     *         不能静默当成 {@code STATIC}，否则运维看到的是一条正常配置
     */
    public LocalStrategy strategy() {
        if (localStrategy == null || localStrategy.isBlank()) {
            throw new com.asialjim.cache4j.core.CacheException(
                    com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC,
                    "策略记录缺少 localStrategy：namespace=" + namespace);
        }
        return LocalStrategy.valueOf(localStrategy);
    }

    /**
     * @return 本地最大条目数（{@code null} 用内建默认）
     */
    public long maxSize() {
        return localMaxSize == null ? BuiltinDefaults.MAX_SIZE : localMaxSize;
    }

    /**
     * @return 本地 TTL，单位由 {@link #localTimeUnit} 决定
     */
    public Duration localTtlDuration() {
        return localTtl == null ? BuiltinDefaults.LOCAL_TTL : duration(localTtl, localTimeUnit, TimeUnit.SECONDS);
    }

    /**
     * @return 本地负副本 TTL 的除数（{@code null} 用内建默认）
     */
    public int nullTtlDivisor() {
        return localNullTtlDivisor == null ? BuiltinDefaults.LOCAL_NULL_TTL_DIVISOR : localNullTtlDivisor;
    }

    /**
     * @return 版本对账参数
     */
    public LocalCacheSpec.VersionSpec versionSpec() {
        return new LocalCacheSpec.VersionSpec(
                duration(versionCheckInterval, localTimeUnit, TimeUnit.SECONDS),
                duration(versionJitter, localTimeUnit, TimeUnit.MILLISECONDS),
                duration(versionRetryBackoff, localTimeUnit, TimeUnit.MILLISECONDS),
                reconcileBatchSize == null ? 1_000 : reconcileBatchSize,
                reconcilePartitions == null ? LocalCacheSpec.VersionSpec.DEFAULT_PARTITIONS : reconcilePartitions);
    }

    /**
     * @return 热 key 参数（仅 DYNAMIC 有值）
     */
    public LocalCacheSpec.DynamicSpec dynamicSpec() {
        return new LocalCacheSpec.DynamicSpec(
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
    }

    /**
     * @return 中央 TTL，单位由 {@link #centralTimeUnit} 决定
     */
    public Duration centralTtlDuration() {
        return centralTtl == null
                ? BuiltinDefaults.CENTRAL_TTL
                : duration(centralTtl, centralTimeUnit, TimeUnit.MINUTES);
    }

    /**
     * @return 中央空值 TTL，单位由 {@link #centralTimeUnit} 决定
     */
    public Duration centralNullTtlDuration() {
        return centralNullTtl == null
                ? BuiltinDefaults.CENTRAL_NULL_TTL
                : duration(centralNullTtl, centralTimeUnit, TimeUnit.SECONDS);
    }

    /**
     * @return 中央不可用时是否降级（默认 {@code true}）
     */
    public boolean failOpenValue() {
        return failOpen == null || failOpen;
    }

    /**
     * 还原为本地规格；{@code null} 字段用内建默认值补齐。
     *
     * @return 本地规格
     */
    public LocalCacheSpec toLocalSpec() {
        LocalStrategy resolved = (localStrategy == null || localStrategy.isBlank())
                ? LocalStrategy.STATIC
                : LocalStrategy.valueOf(localStrategy);
        if (resolved == LocalStrategy.NONE) {
            return LocalCacheSpec.noLocal(namespace);
        }
        LocalCacheSpec.VersionSpec version = versionSpec();
        if (resolved == LocalStrategy.STATIC) {
            return new LocalCacheSpec(namespace, resolved, maxSize(), localTtlDuration(), version, null);
        }
        return new LocalCacheSpec(namespace, resolved, maxSize(), localTtlDuration(), version, dynamicSpec());
    }

    /**
     * 还原为中央规格；{@code null} 字段用内建默认值补齐。
     *
     * @return 中央规格
     */
    public CentralCacheSpec toCentralSpec() {
        return new CentralCacheSpec(
                namespace,
                centralTtlDuration(),
                centralNullTtlDuration(),
                failOpenValue(),
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CacheStrategyProperty that)) {
            return false;
        }
        return Objects.equals(namespace, that.namespace)
                && Objects.equals(description, that.description)
                && Objects.equals(localStrategy, that.localStrategy)
                && Objects.equals(localTimeUnit, that.localTimeUnit)
                && Objects.equals(centralTimeUnit, that.centralTimeUnit)
                && Objects.equals(localMaxSize, that.localMaxSize)
                && Objects.equals(localTtl, that.localTtl)
                && Objects.equals(localNullTtlDivisor, that.localNullTtlDivisor)
                && Objects.equals(versionCheckInterval, that.versionCheckInterval)
                && Objects.equals(versionJitter, that.versionJitter)
                && Objects.equals(versionRetryBackoff, that.versionRetryBackoff)
                && Objects.equals(reconcileBatchSize, that.reconcileBatchSize)
                && Objects.equals(reconcilePartitions, that.reconcilePartitions)
                && Objects.equals(hotThresholdQps, that.hotThresholdQps)
                && Objects.equals(coolQps, that.coolQps)
                && Objects.equals(hotWindow, that.hotWindow)
                && Objects.equals(hotWindowSlices, that.hotWindowSlices)
                && Objects.equals(maxHotKeys, that.maxHotKeys)
                && Objects.equals(hotSyncInterval, that.hotSyncInterval)
                && Objects.equals(hotCooldown, that.hotCooldown)
                && Objects.equals(coordination, that.coordination)
                && Objects.equals(sketchWidthBits, that.sketchWidthBits)
                && Objects.equals(sketchDepth, that.sketchDepth)
                && Objects.equals(hotReportPartitions, that.hotReportPartitions)
                && Objects.equals(hotReportBatchMax, that.hotReportBatchMax)
                && Objects.equals(candidateCapacity, that.candidateCapacity)
                && Objects.equals(globalWindow, that.globalWindow)
                && Objects.equals(globalHotQps, that.globalHotQps)
                && Objects.equals(globalCoolQps, that.globalCoolQps)
                && Objects.equals(globalDemoteWindows, that.globalDemoteWindows)
                && Objects.equals(globalStaleWindows, that.globalStaleWindows)
                && Objects.equals(globalClientEstimate, that.globalClientEstimate)
                && Objects.equals(centralTtl, that.centralTtl)
                && Objects.equals(centralNullTtl, that.centralNullTtl)
                && Objects.equals(centralTtlJitter, that.centralTtlJitter)
                && Objects.equals(failOpen, that.failOpen)
                && Objects.equals(allowNoExpiry, that.allowNoExpiry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, description, localStrategy, localTimeUnit, centralTimeUnit, localMaxSize, localTtl, localNullTtlDivisor, versionCheckInterval, versionJitter, versionRetryBackoff, reconcileBatchSize, reconcilePartitions, hotThresholdQps, coolQps, hotWindow, hotWindowSlices, maxHotKeys, hotSyncInterval, hotCooldown, coordination, sketchWidthBits, sketchDepth, hotReportPartitions, hotReportBatchMax, candidateCapacity, globalWindow, globalHotQps, globalCoolQps, globalDemoteWindows, globalStaleWindows, globalClientEstimate, centralTtl, centralNullTtl, centralTtlJitter, failOpen, allowNoExpiry);
    }
}
