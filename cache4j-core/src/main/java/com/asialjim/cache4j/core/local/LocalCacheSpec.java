package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;

import java.time.Duration;

/**
 * 本地缓存配置。
 *
 * <p>版本参数<strong>只存在</strong>于 {@link #version}；编排门面不重复接收。
 * {@code checkInterval} 是"整个命名空间的最大不一致窗口"的上界，不是后台调度周期。</p>
 *
 * @param namespace 缓存名
 * @param strategy  策略
 * @param maxSize   本地条目上限（{@code NONE} 忽略）
 * @param ttl       本地副本最长存活（{@code NONE} 忽略）
 * @param version   版本对账参数
 * @param dynamic   {@code DYNAMIC} 专属参数；其它策略为 {@code null}
 * @author Asial Jim
 */
public record LocalCacheSpec(
        String namespace,
        LocalStrategy strategy,
        long maxSize,
        Duration ttl,
        /**
         * 负副本（"确认不存在"）的存活时长：{@code min(localTtl, centralNullTtl / N)}。
         *
         * <p>装配时由 {@link #deriveNullTtl} 算好——本地缓存的写路径直接用，
         * 不需要在运行时回头去读中央配置。<strong>{@code ZERO}</strong> 表示不缓存负副本。</p>
         */
        Duration nullTtl,
        VersionSpec version,
        DynamicSpec dynamic
) {

    /**
     * 紧凑构造器：只做非空与基础校验，完整校验见 {@link #validate()}。
     */
    public LocalCacheSpec {
        if (namespace == null || namespace.isBlank()) {
            throw invalid("namespace 不能为空");
        }
        if (strategy == null) {
            throw invalid("strategy 不能为空");
        }
        if (strategy != LocalStrategy.NONE) {
            if (maxSize <= 0) {
                throw invalid("maxSize 必须大于 0：" + maxSize);
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw invalid("本地 ttl 必须为正：" + ttl);
            }
            if (version == null) {
                throw invalid("STATIC / DYNAMIC 的 version 不允许为 null");
            }
        }
        if (strategy == LocalStrategy.DYNAMIC && dynamic == null) {
            throw invalid("DYNAMIC 必须提供 DynamicSpec");
        }
    }

    // ------------------------------------------------------------------ 工厂

    /**
     * 未显式配置时的负副本除子。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = 10;

    /**
     * 派生本地空值副本的存活时长：{@code min(localTtl, centralNullTtl / divisor)}。
     *
     * <p>空值副本只是"防穿透的临时标记"，比中央活得久毫无意义；让本地比中央<strong>更早</strong>
     * 放下标记，避免"中央已能重新回源、本地还在回答不存在"。</p>
     *
     * <p>收敛成一处实现：编排层（{@code Cache4jImpl}）与运维视图
     * （{@code StrategyView}）都要这个值，各写一份必然漂移。</p>
     *
     * @param localTtl       本地非空副本存活（可空）
     * @param centralNullTtl 中央空值存活；{@code null} 或 {@code ZERO} 表示不缓存空值
     * @param divisor        除子 N（≥ 1）
     * @return 本地空值副本存活；不缓存空值时返回 {@link Duration#ZERO}
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
     * 构造 {@code NONE} 策略配置。
     *
     * @param namespace 缓存名
     * @return 配置
     */
    public static LocalCacheSpec noLocal(String namespace) {
        return new LocalCacheSpec(namespace, LocalStrategy.NONE, 0L, null, Duration.ZERO, null, null);
    }

    /**
     * 构造 {@code STATIC} 策略配置，版本参数取默认。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl) {
        return new LocalCacheSpec(namespace, LocalStrategy.STATIC, maxSize, ttl,
                deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR), VersionSpec.defaults(), null);
    }

    /**
     * 构造 {@code STATIC} 策略配置。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl, VersionSpec version) {
        return staticCache(namespace, maxSize, ttl, null, version);
    }

    /**
     * 构造 {@code STATIC} 策略配置，显式给出负副本 TTL。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL（可空 → 按默认除子派生）
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                             VersionSpec version) {
        return new LocalCacheSpec(namespace, LocalStrategy.STATIC, maxSize, ttl,
                nullTtl != null ? nullTtl : deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR), version, null);
    }

    /**
     * 构造 {@code DYNAMIC} 策略配置，版本参数取默认。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, DynamicSpec dynamic) {
        return new LocalCacheSpec(namespace, LocalStrategy.DYNAMIC, maxSize, ttl,
                deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR), VersionSpec.defaults(), dynamic);
    }

    /**
     * 构造 {@code DYNAMIC} 策略配置。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl,
                                              VersionSpec version, DynamicSpec dynamic) {
        return dynamicCache(namespace, maxSize, ttl, null, version, dynamic);
    }

    /**
     * 构造 {@code DYNAMIC} 策略配置，显式给出负副本 TTL。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL（可空 → 按默认除子派生）
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                              VersionSpec version, DynamicSpec dynamic) {
        return new LocalCacheSpec(namespace, LocalStrategy.DYNAMIC, maxSize, ttl,
                nullTtl != null ? nullTtl : deriveNullTtl(ttl, ttl, DEFAULT_NULL_TTL_DIVISOR), version, dynamic);
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 完整参数校验，在构建/启动阶段调用；不合法直接抛 {@link CacheResCode#INVALID_SPEC}。
     *
     * @return this
     */
    public LocalCacheSpec validate() {
        if (strategy == LocalStrategy.NONE) {
            return this;
        }
        version.validate();
        if (dynamic != null) {
            dynamic.validate(this);
        }
        return this;
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }

    // ------------------------------------------------------------------ 版本参数

    /**
     * 版本对账参数。
     *
     * @param checkInterval     命名空间级对账间隔；必须为正
     * @param jitter            对账间隔的**绝对抖动**（例如 100ms 表示 ±100ms 均匀分布）
     * @param retryBackoff      对账失败退避；必须为正
     * @param reconcileBatchSize 一次 {@code HMGET} 的批量大小；必须为正
     * @param reconcilePartitions 对账分区数（2 的幂，默认 16）：一次对账只校验「当前访问 key 所属分区」
     *                            的本地副本，载荷降为 {@code 本地条目数 / N}
     * @author Asial Jim
     */
    public record VersionSpec(
            Duration checkInterval,
            Duration jitter,
            Duration retryBackoff,
            int reconcileBatchSize,
            int reconcilePartitions
    ) {

        /**
         * 默认对账分区数。
         */
        public static final int DEFAULT_PARTITIONS = 16;

        /**
         * 兼容构造：分区数取默认值。
         *
         * @param checkInterval      对账间隔
         * @param jitter             绝对抖动时长
         * @param retryBackoff       失败退避
         * @param reconcileBatchSize 批量大小
         */
        public VersionSpec(Duration checkInterval, Duration jitter, Duration retryBackoff,
                           int reconcileBatchSize) {
            this(checkInterval, jitter, retryBackoff, reconcileBatchSize, DEFAULT_PARTITIONS);
        }

        /**
         * 默认版本参数。
         *
         * @return 默认值
         */
        public static VersionSpec defaults() {
            return new VersionSpec(Duration.ofSeconds(5), Duration.ofMillis(100),
                    Duration.ofMillis(200), 1_000, DEFAULT_PARTITIONS);
        }

        /**
         * 校验。
         *
         * @return this
         */
        public VersionSpec validate() {
            if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
                throw invalid("version.checkInterval 必须为正：" + checkInterval);
            }
            if (jitter == null || jitter.isNegative()) {
                throw invalid("version.jitter 不能为负：" + jitter);
            }
            if (checkInterval != null && !jitter.minus(checkInterval).isNegative()) {
                throw invalid("version.jitter 必须小于 checkInterval，否则抖动会吃掉整个窗口：jitter="
                        + jitter + "，checkInterval=" + checkInterval);
            }
            if (retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative()) {
                throw invalid("version.retryBackoff 必须为正：" + retryBackoff);
            }
            if (reconcileBatchSize <= 0) {
                throw invalid("version.reconcileBatchSize 必须为正：" + reconcileBatchSize);
            }
            if (!com.asialjim.cache4j.core.HashPartitions.isValid(reconcilePartitions)) {
                throw invalid("version.reconcilePartitions 必须为 2 的幂：" + reconcilePartitions);
            }
            return this;
        }
    }

    // ------------------------------------------------------------------ 热度参数

    /**
     * 热度协调模式。
     */
    public enum Coordination {
        /**
         * 单 JVM：本地升降热，不访问中央热度。
         */
        LOCAL,
        /**
         * 多节点：本地只升不降，降热由中央裁决；必须提供 {@code HotKeyCoordinator}。
         */
        GLOBAL
    }

    /**
     * {@code DYNAMIC} 策略参数。
     *
     * @param hotThresholdQps   本地升热阈值（<strong>QPS</strong>；本进程对该 key 的读速率）
     * @param coolQps           本地降热参考阈值（QPS，降热实际只认中央 COOLING）
     * @param window            本地滑动窗口<strong>总长度</strong>
     * @param windowSlices      时间片数（每片 {@code window / windowSlices}）
     * @param maxHotKeys        热点集合上限
     * @param syncInterval      热 key 同步点间隔（访问驱动）
     * @param cooldown          降热冷却期
     * @param coordination      协调模式
     * @param global            中央阈值；{@code coordination == GLOBAL} 时必填
     * @param sketchWidthBits   频率草图宽度位数（宽度 = {@code 1 << bits}，10–16）
     * @param sketchDepth       频率草图深度（≥ 2）
     * @param hotReportPartitions 上报分区数（2 的幂，≥ 2）
     * @param hotReportBatchMax 单次上报 key 数硬上限
     * @param candidateCapacity 预选桶总容量
     * @author Asial Jim
     */
    public record DynamicSpec(
            int hotThresholdQps,
            int coolQps,
            Duration window,
            int windowSlices,
            int maxHotKeys,
            Duration syncInterval,
            Duration cooldown,
            Coordination coordination,
            GlobalSpec global,
            int sketchWidthBits,
            int sketchDepth,
            int hotReportPartitions,
            int hotReportBatchMax,
            int candidateCapacity
    ) {

        /**
         * 频率草图宽度位数下限（1 024 格）。
         */
        public static final int MIN_SKETCH_WIDTH_BITS = 10;

        /**
         * 频率草图宽度位数上限（65 536 格）。
         */
        public static final int MAX_SKETCH_WIDTH_BITS = 16;

        /**
         * @return 频率草图宽度（格数，{@code 1 << sketchWidthBits}）
         */
        public int sketchWidth() {
            return 1 << sketchWidthBits;
        }

        /**
         * @return 时间片时长
         */
        public Duration windowSliceLength() {
            return window.dividedBy(windowSlices);
        }

        /**
         * @return 升热判定用的<strong>窗口内累计次数</strong>阈值（由 QPS 换算）
         */
        public long hotThresholdCount() {
            return (long) hotThresholdQps * window.getSeconds();
        }

        /**
         * @return 预选（candidate）判定用的窗口内累计次数阈值
         */
        public long candidateCount() {
            return candidateQps() * window.getSeconds();
        }

        /**
         * 默认热度参数（单机协调）。
         *
         * @return 默认值
         */
        public static DynamicSpec defaults() {
            return new DynamicSpec(100, 25, Duration.ofSeconds(5), 16, 1_000,
                    Duration.ofSeconds(1), Duration.ofSeconds(30), Coordination.LOCAL, null,
                    12, 4, 16, 16, 1_000);
        }

        /**
         * 返回一个启用中央协调的副本。
         *
         * @param globalSpec 中央阈值
         * @return 新实例
         */
        public DynamicSpec withGlobal(GlobalSpec globalSpec) {
            return new DynamicSpec(hotThresholdQps, coolQps, window, windowSlices, maxHotKeys,
                    syncInterval, cooldown, Coordination.GLOBAL, globalSpec, sketchWidthBits, sketchDepth,
                    hotReportPartitions, hotReportBatchMax, candidateCapacity);
        }

        /**
         * 预选（candidate）阈值，派生值而非手配项。
         *
         * <pre>{@code
         * U = clamp( ceil(global.hotQps / global.clientEstimate), 1, hotThresholdQps - 1 )
         * }</pre>
         *
         * <p><strong>必须严格小于 {@code hotThresholdQps}</strong>：否则任何到达 U 的 key 早已
         * 本地升热，候选桶恒空。两侧都是 QPS 口径，因此<strong>不再需要窗口比例因子</strong>
         * （旧实现里的 {@code window / global.window} 已删除）。</p>
         *
         * <p>候选阶段的判据是"宁多勿少"：CMS 只会高估，高估方向在这里是安全的。</p>
         *
         * @return 预选阈值（QPS）
         */
        public long candidateQps() {
            long upper = hotThresholdQps - 1L;
            if (upper <= 0L) {
                return 1L;
            }
            if (coordination != Coordination.GLOBAL || global == null) {
                return Math.max(1L, upper);
            }
            long share = ceilDiv(global.hotQps(), Math.max(1, global.clientEstimate()));
            return Math.max(1L, Math.min(share, upper));
        }

        private static long ceilDiv(long dividend, long divisor) {
            return (dividend + divisor - 1L) / divisor;
        }

        /**
         * 校验。
         *
         * @param spec 外层配置（需要 {@code maxSize} / {@code ttl}）
         * @return this
         */
        DynamicSpec validate(LocalCacheSpec spec) {
            require(window != null && !window.isZero() && !window.isNegative(), "dynamic.window 必须为正");
            require(windowSlices >= 2, "dynamic.windowSlices 必须 >= 2：" + windowSlices);
            require(window.toMillis() / windowSlices >= 1L,
                    "window / windowSlices 必须 >= 1ms（否则时间片失去分辨率）：window=" + window
                            + "，slices=" + windowSlices);
            require(maxHotKeys >= 1 && maxHotKeys <= spec.maxSize(),
                    "要求 1 <= maxHotKeys <= maxSize：" + maxHotKeys);
            require(hotThresholdQps >= 1, "dynamic.hotThresholdQps 必须 >= 1：" + hotThresholdQps);
            require(coolQps >= 0 && coolQps < hotThresholdQps,
                    "要求 0 <= coolQps < hotThresholdQps：" + coolQps);
            require(syncInterval != null && !syncInterval.isZero() && !syncInterval.isNegative(),
                    "dynamic.syncInterval 必须为正");
            require(cooldown != null && !cooldown.isNegative(), "dynamic.cooldown 不能为负");
            require(sketchWidthBits >= MIN_SKETCH_WIDTH_BITS && sketchWidthBits <= MAX_SKETCH_WIDTH_BITS,
                    "dynamic.sketchWidthBits 必须在 [" + MIN_SKETCH_WIDTH_BITS + ", "
                            + MAX_SKETCH_WIDTH_BITS + "]：" + sketchWidthBits);
            require(sketchDepth >= 2, "dynamic.sketchDepth 必须 >= 2：" + sketchDepth);
            require(isPowerOfTwo(hotReportPartitions) && hotReportPartitions >= 2,
                    "dynamic.hotReportPartitions 必须为 2 的幂且 >= 2：" + hotReportPartitions);
            require(hotReportBatchMax >= 1 && hotReportBatchMax <= maxHotKeys,
                    "要求 1 <= hotReportBatchMax <= maxHotKeys：" + hotReportBatchMax);
            require(candidateCapacity >= 1, "dynamic.candidateCapacity 必须 >= 1：" + candidateCapacity);

            if (coordination == Coordination.GLOBAL) {
                if (global == null) {
                    throw invalid("coordination=GLOBAL 时必须提供 dynamic.global");
                }
                global.validate();
                require(hotThresholdQps >= 2, "GLOBAL 模式下 hotThresholdQps 必须 >= 2");
                require(!cooldown.minus(global.demoteWindows() > 0
                                ? global.window().multipliedBy(global.demoteWindows())
                                : Duration.ZERO)
                                .isNegative(),
                        "要求 cooldown >= demoteWindows × global.window，避免本地刚解冻、中央又判 HOT 形成慢速抖动");
            } else {
                require(global == null, "coordination=LOCAL 时 dynamic.global 应留空");
            }
            return this;
        }

        private static boolean isPowerOfTwo(int value) {
            return value > 0 && (value & (value - 1)) == 0;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw invalid(message);
            }
        }
    }

    /**
     * 中央（集群级）阈值。
     *
     * @param window           中央窗口
     * @param hotThreshold     集群级升热阈值
     * @param coolThreshold    集群级降热阈值（必须小于升热阈值，形成滞回）
     * @param demoteWindows    连续低于冷阈值的窗口数
     * @param staleWindows     无上报多少个窗口后判 {@code COOLING}
     * @param clientEstimate   连接同一套中央缓存的客户端实例数（推导预选阈值）
     * @author Asial Jim
     */
    public record GlobalSpec(
            Duration window,
            long hotQps,
            long coolQps,
            int demoteWindows,
            int staleWindows,
            int clientEstimate
    ) {

        /**
         * @return 判定用的窗口内累计次数阈值（由 QPS 换算）
         */
        public long hotCount() {
            return hotQps * window.getSeconds();
        }

        /**
         * @return 冷却判定用的窗口内累计次数阈值（由 QPS 换算）
         */
        public long coolCount() {
            return coolQps * window.getSeconds();
        }

        /**
         * 构造并补充常用的派生默认值（冷阈值取热阈值的 1/4）。
         *
         * @param window         中央窗口
         * @param hotQps         升热阈值（QPS）
         * @param clientEstimate 客户端实例数
         * @return 配置
         */
        public static GlobalSpec of(Duration window, long hotQps, int clientEstimate) {
            return new GlobalSpec(window, hotQps, Math.max(1L, hotQps / 4L), 3, 3, clientEstimate);
        }

        /**
         * 校验。
         *
         * @return this
         */
        public GlobalSpec validate() {
            require(window != null && !window.isZero() && !window.isNegative(), "global.window 必须为正");
            require(hotQps > 0L, "global.hotQps 必须为正");
            require(coolQps >= 0L && coolQps < hotQps,
                    "要求 0 <= coolQps < hotQps");
            require(demoteWindows >= 1, "global.demoteWindows 必须 >= 1");
            require(staleWindows >= 1, "global.staleWindows 必须 >= 1");
            require(clientEstimate >= 1, "global.clientEstimate 必须 >= 1");
            return this;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw invalid(message);
            }
        }
    }
}
