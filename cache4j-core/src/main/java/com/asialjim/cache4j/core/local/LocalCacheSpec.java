package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.Coordination;

import java.time.Duration;

/**
 * 本地配置的<strong>过渡壳</strong>：内部只有一份 {@link CacheStrategy}，所有取值转发过去。
 *
 * <p>它不再是"配置的来源"，只是旧调用面（{@code spec.ttl()} / {@code spec.version()} …）
 * 的适配层。运行期真正被引用的是 {@code CacheStrategy}——见 {@code Cache4j.cacheStrategy()}。
 * 这样做是为了让"删掉这个类"变成纯删除，而不是一次跨 60 个文件的类型手术。</p>
 *
 * <p>{@link VersionSpec} / {@link DynamicSpec} / {@link GlobalSpec} 同理：它们是
 * "构造 CacheStrategy 的参数分组"与"从策略派生的只读视图"，不再承担配置语义。</p>
 *
 * @author Asial Jim
 */
public final class LocalCacheSpec {

    /**
     * 未显式配置时的负副本除子。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = CacheStrategy.DEFAULT_NULL_TTL_DIVISOR;

    private final CacheStrategy strategy;

    private LocalCacheSpec(CacheStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * @param strategy 完整策略
     * @return 本地配置视图
     */
    public static LocalCacheSpec of(CacheStrategy strategy) {
        return new LocalCacheSpec(strategy);
    }

    /**
     * @return 完整策略
     */
    public CacheStrategy toStrategy() {
        return strategy;
    }

    public String namespace() {
        return strategy.namespace();
    }

    public LocalStrategy strategy() {
        return strategy.localStrategy();
    }

    public long maxSize() {
        return strategy.localMaxSize();
    }

    public Duration ttl() {
        return strategy.localTtl();
    }

    public Duration nullTtl() {
        return strategy.localNullTtl();
    }

    public VersionSpec version() {
        return strategy.localStrategy() == LocalStrategy.NONE ? null : VersionSpec.of(strategy);
    }

    public DynamicSpec dynamic() {
        return strategy.isDynamic() ? DynamicSpec.of(strategy) : null;
    }

    /**
     * 完整校验：版本参数 + 热度参数（{@code NONE} 不校验——它没有这些概念）。
     *
     * @return 自身
     */
    public LocalCacheSpec validate() {
        if (strategy.localStrategy() == LocalStrategy.NONE) {
            return this;
        }
        version().validate();
        DynamicSpec d = dynamic();
        if (d != null) {
            d.validate(this);
        }
        return this;
    }

    static com.asialjim.cache4j.core.CacheException invalid(String message) {
        return new com.asialjim.cache4j.core.CacheException(
                com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC, message);
    }

    /**
     * 派生负副本 TTL。
     *
     * @param localTtl       本地非空副本存活
     * @param centralNullTtl 中央空值存活
     * @param divisor        除子
     * @return 本地空值副本存活
     */
    public static Duration deriveNullTtl(Duration localTtl, Duration centralNullTtl, int divisor) {
        return CacheStrategy.deriveNullTtl(localTtl, centralNullTtl, divisor);
    }

    /**
     * {@code NONE}：本地不缓存。
     *
     * @param namespace 缓存名
     * @return 配置
     */
    public static LocalCacheSpec noLocal(String namespace) {
        return of(CacheStrategy.noLocal(namespace));
    }

    /**
     * {@code STATIC}。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl));
    }

    /**
     * {@code STATIC}，显式给版本参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl, VersionSpec version) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl, version));
    }

    /**
     * {@code STATIC}，显式给负副本 TTL 与版本参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                             VersionSpec version) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl, nullTtl, version));
    }

    /**
     * {@code DYNAMIC}。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, DynamicSpec dynamic) {
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, dynamic));
    }

    /**
     * {@code DYNAMIC}，显式给版本参数。
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
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, version, dynamic));
    }

    /**
     * {@code DYNAMIC}，显式给负副本 TTL、版本参数与热度参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                              VersionSpec version, DynamicSpec dynamic) {
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, nullTtl, version, dynamic));
    }

    // ------------------------------------------------------------------ 参数分组 / 派生视图

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
        /**
         * 从策略派生。
         *
         * @param strategy 策略
         * @return 版本参数视图
         */
        public static VersionSpec of(com.asialjim.cache4j.core.CacheStrategy strategy) {
            return new VersionSpec(strategy.versionCheckInterval(), strategy.versionJitter(),
                    strategy.versionRetryBackoff(), strategy.reconcileBatchSize(),
                    strategy.reconcilePartitions());
        }

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
            com.asialjim.cache4j.core.Coordination coordination,
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
        /**
         * 从策略派生。
         *
         * @param strategy 策略
         * @return 热度参数视图
         */
        public static DynamicSpec of(com.asialjim.cache4j.core.CacheStrategy strategy) {
            GlobalSpec global = strategy.globalWindow() == null ? null
                    : new GlobalSpec(strategy.globalWindow(), strategy.globalHotQps(), strategy.globalCoolQps(),
                    strategy.globalDemoteWindows(), strategy.globalStaleWindows(),
                    strategy.globalClientEstimate());
            return new DynamicSpec((int) strategy.hotThresholdQps(), (int) strategy.coolQps(),
                    strategy.hotWindow(), strategy.hotWindowSlices(), strategy.maxHotKeys(),
                    strategy.hotSyncInterval(), strategy.hotCooldown(), strategy.coordination(),
                    global, strategy.sketchWidthBits(), strategy.sketchDepth(),
                    strategy.hotReportPartitions(), strategy.hotReportBatchMax(),
                    strategy.candidateCapacity());
        }

        public static DynamicSpec defaults() {
            return new DynamicSpec(100, 25, Duration.ofSeconds(5), 16, 1_000,
                    Duration.ofSeconds(1), Duration.ofSeconds(30), com.asialjim.cache4j.core.Coordination.LOCAL, null,
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
                    syncInterval, cooldown, com.asialjim.cache4j.core.Coordination.GLOBAL, globalSpec, sketchWidthBits, sketchDepth,
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
            if (coordination != com.asialjim.cache4j.core.Coordination.GLOBAL || global == null) {
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

            if (coordination == com.asialjim.cache4j.core.Coordination.GLOBAL) {
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
