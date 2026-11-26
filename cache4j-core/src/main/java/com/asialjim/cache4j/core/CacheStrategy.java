package com.asialjim.cache4j.core;

import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * <strong>一个 namespace 的全部缓存策略</strong>——运行期唯一使用的配置类型。
 *
 * <p>它是<strong>不可变值类型</strong>：字段全部是强类型（{@code Duration} / 枚举 / 基本类型），
 * 没有 {@code Supplier}、没有 {@code String→枚举} 的逐次解析、没有可空包装类型的拆箱。</p>
 *
 * <p><strong>为什么不用 {@code Supplier} 做"活配置"</strong>：那会让每次缓存操作都要
 * 把几十个 Supplier 求值一遍才能拿到一份一致快照，且 {@code equals} / 日志 / 序列化全失效。
 * 热更新用"替换整个引用"实现——一次 volatile 读即可拿到一致快照，零分配：</p>
 *
 * <pre>
 * AtomicReference&lt;CacheStrategy&gt; strategyRef;
 * CacheStrategy s = strategyRef.get();     // 一次读，天然一致
 * </pre>
 *
 * <p>字段全部<strong>扁平</strong>：版本对账、热 key、全局热 key、中央这几组语义靠注释分段，
 * 不再用 {@code VersionSpec} / {@code DynamicSpec} / {@code GlobalSpec} 嵌套——
 * 嵌套只是把同一个配置拆到四个 record 里，读的时候要跨文件拼。</p>
 *
 * @param namespace             命名空间
 * @param description           用途说明（运维据此判断这个缓存干什么用）
 * @param localStrategy         本地策略
 * @param localMaxSize          本地条目上限；{@code NONE} 时为 0
 * @param localTtl              本地条目存活；{@code NONE} 时为 {@code null}
 * @param localNullTtl          负副本存活 = {@code min(localTtl, centralNullTtl / divisor)}；
 *                              {@code ZERO} 表示不缓存负副本
 * @param versionCheckInterval  对账检查间隔
 * @param versionJitter         检查间隔抖动
 * @param versionRetryBackoff   对账失败退避
 * @param reconcileBatchSize    单批取版本的 key 数
 * @param reconcilePartitions   对账分区数（2 的幂）
 * @param hotThresholdQps       升热阈值（QPS）
 * @param coolQps               降热判定用的 QPS（GLOBAL 模式下本地只升不降）
 * @param hotWindow             频次统计窗口
 * @param hotWindowSlices       窗口切片数
 * @param maxHotKeys            热点集合上限
 * @param hotSyncInterval       热度上报间隔
 * @param hotCooldown           降热后的冷却时长（抑制"降了立刻又升"的抖动）
 * @param coordination          热 key 协调方式
 * @param sketchWidthBits       Count-Min Sketch 宽度（2 的幂）
 * @param sketchDepth           Count-Min Sketch 深度
 * @param hotReportPartitions   上报分区数
 * @param hotReportBatchMax     单次上报的 key 上限
 * @param candidateCapacity     候选桶容量（per-key 窗口表的上限）
 * @param globalWindow          全局热度窗口；{@code null} 表示不启用全局热 key
 * @param globalHotQps          全局升热阈值
 * @param globalCoolQps         全局降热阈值
 * @param globalDemoteWindows   连续多少个窗口低于阈值才降热
 * @param globalStaleWindows    多少窗口无上报视为失效
 * @param globalClientEstimate  单客户端估算份额（用于把全局阈值折算成本地预选阈值）
 * @param centralTtl            中央条目存活
 * @param centralNullTtl        中央负副本存活；{@code ZERO} 表示不缓存
 * @param centralTtlJitter      中央 TTL 抖动比例（0–0.5）
 * @param failOpen              中央不可用时是否放行
 * @param allowNoExpiry         是否允许"永不过期"（{@code centralTtl} 为 0 时）
 * @author Asial Jim
 */
public record CacheStrategy(
        String namespace,
        String description,
        LocalStrategy localStrategy,

        // --- 本地容量与 TTL
        long localMaxSize,
        Duration localTtl,
        Duration localNullTtl,

        // --- 版本对账
        Duration versionCheckInterval,
        Duration versionJitter,
        Duration versionRetryBackoff,
        int reconcileBatchSize,
        int reconcilePartitions,

        // --- 热 key（本地）
        long hotThresholdQps,
        long coolQps,
        Duration hotWindow,
        int hotWindowSlices,
        int maxHotKeys,
        Duration hotSyncInterval,
        Duration hotCooldown,
        Coordination coordination,
        int sketchWidthBits,
        int sketchDepth,
        int hotReportPartitions,
        int hotReportBatchMax,
        int candidateCapacity,

        // --- 热 key（全局）
        Duration globalWindow,
        long globalHotQps,
        long globalCoolQps,
        int globalDemoteWindows,
        int globalStaleWindows,
        int globalClientEstimate,

        // --- 中央
        Duration centralTtl,
        Duration centralNullTtl,
        double centralTtlJitter,
        boolean failOpen,
        boolean allowNoExpiry
) {

    public CacheStrategy {
        if (namespace == null || namespace.isBlank()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "namespace 不能为空");
        }
        if (localStrategy == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "localStrategy 不能为空");
        }
        if (localStrategy != LocalStrategy.NONE) {
            if (localMaxSize <= 0) {
                throw new CacheException(CacheResCode.INVALID_SPEC, "localMaxSize 必须大于 0：" + localMaxSize);
            }
            if (localTtl == null || localTtl.isZero() || localTtl.isNegative()) {
                throw new CacheException(CacheResCode.INVALID_SPEC, "本地 ttl 必须为正：" + localTtl);
            }
            if (localNullTtl == null) {
                throw new CacheException(CacheResCode.INVALID_SPEC, "localNullTtl 不能为空（ZERO 表示不缓存负副本）");
            }
            if (versionCheckInterval == null || versionCheckInterval.isZero() || versionCheckInterval.isNegative()) {
                throw new CacheException(CacheResCode.INVALID_SPEC,
                        "versionCheckInterval 必须为正：" + versionCheckInterval);
            }
        }
        if (centralTtl == null || centralTtl.isNegative()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralTtl 不能为负：" + centralTtl);
        }
        if (centralNullTtl == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralNullTtl 不能为空");
        }
        if (centralTtlJitter < 0 || centralTtlJitter > 0.5) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralTtlJitter 必须在 [0, 0.5]：" + centralTtlJitter);
        }
    }

    /**
     * 完整校验：版本参数 + 热度参数（{@code NONE} 不校验——它没有这些概念）。
     *
     * <p>基础非空/正数校验在紧凑构造器里做；这里补的是"跨字段约束"，
     * 例如 {@code hotThresholdQps > coolQps}、{@code sketchWidthBits >= 10}、
     * {@code cooldown >= demoteWindows × globalWindow}。</p>
     *
     * @return 自身
     */
    public CacheStrategy validate() {
        if (localStrategy == LocalStrategy.NONE) {
            return this;
        }
        VersionSpec.of(this).validate();
        if (isDynamic()) {
            DynamicSpec.of(this).validate(localMaxSize);
        }
        return this;
    }

    /**
     * @return 是否有本地缓存（{@code STATIC} / {@code DYNAMIC}）
     */
    public boolean hasLocal() {
        return localStrategy != LocalStrategy.NONE;
    }

    /**
     * @return 是否为动态本地策略
     */
    public boolean isDynamic() {
        return localStrategy == LocalStrategy.DYNAMIC;
    }

    /**
     * @return 中央是否缓存负副本
     */
    public boolean nullCachingEnabled() {
        return centralNullTtl != null && !centralNullTtl.isZero();
    }


    /**
     * 替换中央侧字段（本地与热 key 部分保持原样）。
     *
     * @param centralTtl     中央 TTL
     * @param centralNullTtl 中央负副本 TTL
     * @param failOpen       中央不可用时是否放行
     * @return 新策略
     */
    public CacheStrategy withCentral(Duration centralTtl, Duration centralNullTtl, boolean failOpen) {
        return new CacheStrategy(namespace, description, localStrategy,
                localMaxSize, localTtl, localNullTtl,
                versionCheckInterval, versionJitter, versionRetryBackoff,
                reconcileBatchSize, reconcilePartitions,
                hotThresholdQps, coolQps, hotWindow, hotWindowSlices, maxHotKeys,
                hotSyncInterval, hotCooldown, coordination, sketchWidthBits, sketchDepth,
                hotReportPartitions, hotReportBatchMax, candidateCapacity,
                globalWindow, globalHotQps, globalCoolQps, globalDemoteWindows, globalStaleWindows,
                globalClientEstimate,
                centralTtl, centralNullTtl, centralTtlJitter, failOpen, allowNoExpiry);
    }

    // ------------------------------------------------------------------ 工厂（装配与测试共用）

    /**
     * 未显式配置时的负副本除子。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = 10;

    /**
     * 派生负副本存活时长：{@code min(localTtl, centralNullTtl / divisor)}。
     *
     * <p>负副本是"防穿透的临时标记"，比中央活得久毫无意义——让本地比中央更早放下标记，
     * 避免"中央已能重新回源、本地还在说不存在"。</p>
     *
     * @param localTtl       本地非空副本存活（可空）
     * @param centralNullTtl 中央空值存活；{@code null} 或 {@code ZERO} 表示不缓存空值
     * @param divisor        除子 N（≥ 1）
     * @return 本地空值副本存活
     */
    public static Duration deriveNullTtl(Duration localTtl, Duration centralNullTtl, int divisor) {
        if (centralNullTtl == null || centralNullTtl.isZero()) {
            return Duration.ZERO;
        }
        Duration derived = centralNullTtl.dividedBy(Math.max(1, divisor));
        if (localTtl == null) {
            return derived;
        }
        return derived.compareTo(localTtl) < 0 ? derived : localTtl;
    }

    /**
     * {@code NONE}：本地不缓存。
     *
     * @param namespace 命名空间
     * @return 策略
     */
    public static CacheStrategy noLocal(String namespace) {
        return build(namespace, LocalStrategy.NONE, 0L, null, Duration.ZERO, null, null);
    }

    /**
     * {@code STATIC}。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @return 策略
     */
    public static CacheStrategy staticCache(String namespace, long maxSize, Duration ttl) {
        return staticCache(namespace, maxSize, ttl, null, VersionSpec.defaults());
    }

    /**
     * {@code STATIC}，显式给版本参数。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @return 策略
     */
    public static CacheStrategy staticCache(String namespace, long maxSize, Duration ttl, VersionSpec version) {
        return staticCache(namespace, maxSize, ttl, null, version);
    }

    /**
     * {@code STATIC}，显式给负副本 TTL 与版本参数。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL（可空 → 按默认除子派生）
     * @param version   版本参数
     * @return 策略
     */
    public static CacheStrategy staticCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                            VersionSpec version) {
        return build(namespace, LocalStrategy.STATIC, maxSize, ttl,
                nullTtl != null ? nullTtl : deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR),
                version == null ? VersionSpec.defaults() : version, null);
    }

    /**
     * {@code DYNAMIC}。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param dynamic   热度参数
     * @return 策略
     */
    public static CacheStrategy dynamicCache(String namespace, long maxSize, Duration ttl, DynamicSpec dynamic) {
        return dynamicCache(namespace, maxSize, ttl, null, VersionSpec.defaults(), dynamic);
    }

    /**
     * {@code DYNAMIC}，显式给版本参数。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 策略
     */
    public static CacheStrategy dynamicCache(String namespace, long maxSize, Duration ttl,
                                             VersionSpec version, DynamicSpec dynamic) {
        return dynamicCache(namespace, maxSize, ttl, null, version, dynamic);
    }

    /**
     * {@code DYNAMIC}，显式给负副本 TTL、版本参数与热度参数。
     *
     * @param namespace 命名空间
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL（可空 → 按默认除子派生）
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 策略
     */
    public static CacheStrategy dynamicCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                             VersionSpec version, DynamicSpec dynamic) {
        return build(namespace, LocalStrategy.DYNAMIC, maxSize, ttl,
                nullTtl != null ? nullTtl : deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR),
                version == null ? VersionSpec.defaults() : version, dynamic);
    }

    /**
     * 统一构造：中央侧取内建默认（10 分钟 TTL / 1 分钟负副本 / failOpen=true）。
     *
     * @param namespace 命名空间
     * @param strategy  本地策略
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL
     * @param version   版本参数
     * @param dynamic   热度参数（非 DYNAMIC 传 null）
     * @return 策略
     */
    private static CacheStrategy build(String namespace, LocalStrategy strategy, long maxSize, Duration ttl,
                                       Duration nullTtl, VersionSpec version, DynamicSpec dynamic) {
        VersionSpec v = version == null ? VersionSpec.defaults() : version;
        GlobalSpec g = dynamic == null ? null : dynamic.global();
        return new CacheStrategy(
                namespace, null, strategy, maxSize, ttl,
                nullTtl != null ? nullTtl : Duration.ZERO,
                v.checkInterval(), v.jitter(), v.retryBackoff(),
                v.reconcileBatchSize(), v.reconcilePartitions(),
                dynamic == null ? 0L : dynamic.hotThresholdQps(),
                dynamic == null ? 0L : dynamic.coolQps(),
                dynamic == null ? null : dynamic.window(),
                dynamic == null ? 16 : dynamic.windowSlices(),
                dynamic == null ? 1_000 : dynamic.maxHotKeys(),
                dynamic == null ? Duration.ofSeconds(1) : dynamic.syncInterval(),
                dynamic == null ? Duration.ofSeconds(30) : dynamic.cooldown(),
                dynamic == null ? Coordination.LOCAL : dynamic.coordination(),
                dynamic == null ? 14 : dynamic.sketchWidthBits(),
                dynamic == null ? 4 : dynamic.sketchDepth(),
                dynamic == null ? 16 : dynamic.hotReportPartitions(),
                dynamic == null ? 64 : dynamic.hotReportBatchMax(),
                dynamic == null ? 1 << 12 : dynamic.candidateCapacity(),
                g == null ? null : g.window(),
                g == null ? 100L : g.hotQps(),
                g == null ? 25L : g.coolQps(),
                g == null ? 1 : g.demoteWindows(),
                g == null ? 6 : g.staleWindows(),
                g == null ? 1 : g.clientEstimate(),
                Duration.ofMinutes(10), Duration.ofMinutes(1), 0D, true, false);
    }

    // ------------------------------------------------------------------ 派生量

    /**
     * @return Count-Min Sketch 的宽度（{@code sketchWidthBits} 的 2 的幂）
     */
    public int sketchWidth() {
        return 1 << sketchWidthBits;
    }

    /**
     * @return 时间片时长
     */
    public Duration windowSliceLength() {
        return hotWindow.dividedBy(hotWindowSlices);
    }

    /**
     * @return 升热判定用的<strong>窗口内累计次数</strong>阈值（由 QPS 换算）
     */
    public long hotThresholdCount() {
        return hotThresholdQps * hotWindow.getSeconds();
    }

    /**
     * @return 预选（candidate）判定用的窗口内累计次数阈值
     */
    public long candidateCount() {
        return candidateQps() * hotWindow.getSeconds();
    }

    /**
     * 预选阈值（QPS）：{@code clamp(ceil(globalHotQps / globalClientEstimate), 1, hotThresholdQps - 1)}。
     *
     * <p>必须严格小于 {@code hotThresholdQps}：否则任何到达它的 key 早已本地升热，候选桶恒空。
     * 候选阶段"宁多勿少"——CMS 只会高估，高估在候选阶段是安全方向。</p>
     *
     * @return 预选阈值（QPS）
     */
    public long candidateQps() {
        long upper = hotThresholdQps - 1L;
        if (upper <= 0L) {
            return 1L;
        }
        if (coordination != Coordination.GLOBAL || globalWindow == null) {
            return Math.max(1L, upper);
        }
        long estimate = Math.max(1, globalClientEstimate);
        long share = (globalHotQps + estimate - 1L) / estimate;
        return Math.max(1L, Math.min(share, upper));
    }

    /**
     * @param namespace 命名空间
     * @return 构建器（本地侧默认 {@code STATIC}，中央侧取内建默认）
     */
    public static Builder builder(String namespace) {
        return new Builder(namespace);
    }

    /**
     * 便捷构建器：只关心中央字段时用它，本地侧会被赋内建默认值。
     */
    public static final class Builder {

        private final String namespace;
        private Duration ttl = Duration.ofMinutes(10);
        private Duration nullTtl = Duration.ZERO;   // 默认关闭空值缓存（与策略的 nullCachingEnabled 语义一致）
        private boolean failOpen = true;
        private double ttlJitter = 0D;
        private boolean allowNoExpiry = false;

        private Builder(String namespace) {
            this.namespace = namespace;
        }

        /**
         * @param value 中央 TTL
         * @return this
         */
        public Builder ttl(Duration value) {
            this.ttl = value;
            return this;
        }

        /**
         * @param value 中央负副本 TTL
         * @return this
         */
        public Builder nullTtl(Duration value) {
            this.nullTtl = value;
            return this;
        }

        /**
         * @param value 中央不可用时是否放行
         * @return this
         */
        public Builder failOpen(boolean value) {
            this.failOpen = value;
            return this;
        }

        /**
         * @param value TTL 抖动比例
         * @return this
         */
        public Builder ttlJitter(double value) {
            this.ttlJitter = value;
            return this;
        }

        /**
         * @param value 是否允许永不过期
         * @return this
         */
        public Builder allowNoExpiry(boolean value) {
            this.allowNoExpiry = value;
            return this;
        }

        /**
         * @return 策略（本地侧为 STATIC 默认值，中央侧按本构建器）
         */
        public CacheStrategy build() {
            CacheStrategy base = staticCache(namespace, 1_000L, Duration.ofMinutes(1));
            return new CacheStrategy(base.namespace(), base.description(), base.localStrategy(),
                    base.localMaxSize(), base.localTtl(), base.localNullTtl(),
                    base.versionCheckInterval(), base.versionJitter(), base.versionRetryBackoff(),
                    base.reconcileBatchSize(), base.reconcilePartitions(),
                    base.hotThresholdQps(), base.coolQps(), base.hotWindow(), base.hotWindowSlices(),
                    base.maxHotKeys(), base.hotSyncInterval(), base.hotCooldown(), base.coordination(),
                    base.sketchWidthBits(), base.sketchDepth(), base.hotReportPartitions(),
                    base.hotReportBatchMax(), base.candidateCapacity(),
                    base.globalWindow(), base.globalHotQps(), base.globalCoolQps(),
                    base.globalDemoteWindows(), base.globalStaleWindows(), base.globalClientEstimate(),
                    ttl, nullTtl, ttlJitter, failOpen, allowNoExpiry);
        }
    }
}
