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
     * 构造 {@code NONE} 策略配置。
     *
     * @param namespace 缓存名
     * @return 配置
     */
    public static LocalCacheSpec noLocal(String namespace) {
        return new LocalCacheSpec(namespace, LocalStrategy.NONE, 0L, null, null, null);
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
        return new LocalCacheSpec(namespace, LocalStrategy.STATIC, maxSize, ttl, VersionSpec.defaults(), null);
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
        return new LocalCacheSpec(namespace, LocalStrategy.STATIC, maxSize, ttl, version, null);
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
        return new LocalCacheSpec(namespace, LocalStrategy.DYNAMIC, maxSize, ttl, VersionSpec.defaults(), dynamic);
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
        return new LocalCacheSpec(namespace, LocalStrategy.DYNAMIC, maxSize, ttl, version, dynamic);
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
     * @param jitter            对账间隔抖动比例（0–0.5）
     * @param retryBackoff      对账失败退避；必须为正
     * @param reconcileBatchSize 一次 {@code HMGET} 的批量大小；必须为正
     * @param reconcilePartitions 对账分区数（2 的幂，默认 16）：一次对账只校验「当前访问 key 所属分区」
     *                            的本地副本，载荷降为 {@code 本地条目数 / N}
     * @author Asial Jim
     */
    public record VersionSpec(
            Duration checkInterval,
            double jitter,
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
         * @param jitter             抖动比例
         * @param retryBackoff       失败退避
         * @param reconcileBatchSize 批量大小
         */
        public VersionSpec(Duration checkInterval, double jitter, Duration retryBackoff,
                           int reconcileBatchSize) {
            this(checkInterval, jitter, retryBackoff, reconcileBatchSize, DEFAULT_PARTITIONS);
        }

        /**
         * 默认版本参数。
         *
         * @return 默认值
         */
        public static VersionSpec defaults() {
            return new VersionSpec(Duration.ofSeconds(1), 0.1D, Duration.ofMillis(200), 1_000,
                    DEFAULT_PARTITIONS);
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
            if (jitter < 0D || jitter > 0.5D) {
                throw invalid("version.jitter 必须在 [0, 0.5]：" + jitter);
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
     * @param localHotThreshold  本地窗口频次阈值（1–255，达到即升热）
     * @param window             本地频率窗口
     * @param maxHotKeys         热点集合上限
     * @param hotTtl             热点 key 本地最长存活
     * @param syncInterval       热 key 同步点间隔（访问驱动）
     * @param cooldown           降热冷却期
     * @param coordination       协调模式
     * @param global             中央阈值；{@code coordination == GLOBAL} 时必填
     * @param sketchWidth        频率草图宽度（2 的幂，≥ 1024）
     * @param sketchDepth        频率草图深度（≥ 2）
     * @param hotReportPartitions 上报分区数（2 的幂，≥ 2）
     * @param hotReportBatchMax  单次上报 key 数硬上限
     * @param candidateCapacity  预选桶总容量
     * @author Asial Jim
     */
    public record DynamicSpec(
            int localHotThreshold,
            Duration window,
            int maxHotKeys,
            Duration hotTtl,
            Duration syncInterval,
            Duration cooldown,
            Coordination coordination,
            GlobalSpec global,
            int sketchWidth,
            int sketchDepth,
            int hotReportPartitions,
            int hotReportBatchMax,
            int candidateCapacity
    ) {

        /**
         * 默认热度参数（单机协调）。
         *
         * @return 默认值
         */
        public static DynamicSpec defaults() {
            return new DynamicSpec(5, Duration.ofSeconds(10), 1_000, Duration.ofSeconds(60),
                    Duration.ofSeconds(1), Duration.ofSeconds(30), Coordination.LOCAL, null,
                    4_096, 4, 16, 64, 1_000);
        }

        /**
         * 返回一个启用中央协调的副本。
         *
         * @param globalSpec 中央阈值
         * @return 新实例
         */
        public DynamicSpec withGlobal(GlobalSpec globalSpec) {
            return new DynamicSpec(localHotThreshold, window, maxHotKeys, hotTtl, syncInterval, cooldown,
                    Coordination.GLOBAL, globalSpec, sketchWidth, sketchDepth,
                    hotReportPartitions, hotReportBatchMax, candidateCapacity);
        }

        /**
         * 预选（candidate）阈值，派生值而非手配项。
         *
         * <pre>{@code
         * U = clamp( ceil(global.hotThreshold / global.clientEstimate) 换算到本地窗口,
         *            1, localHotThreshold - 1 )
         * }</pre>
         *
         * <p><strong>必须严格小于 {@code localHotThreshold}</strong>：否则任何到达 U 的 key 早已
         * 本地升热，候选桶恒空。均匀分布下全局热 key 的单节点份额是
         * {@code hotThreshold / clientEstimate}，只有 {@code U <= 该份额} 才能保证它在每个节点
         * 都落进候选桶、被上报、由中央判 {@code HOT}。</p>
         *
         * @return 预选阈值
         */
        public long candidateThreshold() {
            long upper = localHotThreshold - 1L;
            if (upper <= 0L) {
                return 1L;
            }
            if (coordination != Coordination.GLOBAL || global == null || global.window() == null) {
                return Math.max(1L, upper);
            }
            double scale = (double) window.toMillis() / global.window().toMillis();
            double share = global.hotThreshold() / (double) Math.max(1, global.clientEstimate()) * scale;
            long threshold = (long) Math.ceil(share);
            return Math.max(1L, Math.min(threshold, upper));
        }

        /**
         * 校验。
         *
         * @param spec 外层配置（需要 {@code maxSize} / {@code ttl}）
         * @return this
         */
        DynamicSpec validate(LocalCacheSpec spec) {
            require(window != null && !window.isZero() && !window.isNegative(), "dynamic.window 必须为正");
            require(maxHotKeys >= 1 && maxHotKeys <= spec.maxSize(),
                    "要求 1 <= maxHotKeys <= maxSize：" + maxHotKeys);
            require(localHotThreshold >= 1 && localHotThreshold <= 255,
                    "要求 1 <= localHotThreshold <= 255：" + localHotThreshold);
            require(hotTtl != null && !hotTtl.isZero() && !hotTtl.isNegative(), "dynamic.hotTtl 必须为正");
            require(syncInterval != null && !syncInterval.isZero() && !syncInterval.isNegative(),
                    "dynamic.syncInterval 必须为正");
            require(cooldown != null && !cooldown.isNegative(), "dynamic.cooldown 不能为负");
            require(isPowerOfTwo(sketchWidth) && sketchWidth >= 1_024,
                    "dynamic.sketchWidth 必须为 2 的幂且 >= 1024：" + sketchWidth);
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
                require(localHotThreshold >= 2, "GLOBAL 模式下 localHotThreshold 必须 >= 2");
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
            long hotThreshold,
            long coolThreshold,
            int demoteWindows,
            int staleWindows,
            int clientEstimate
    ) {

        /**
         * 构造并补充常用的派生默认值（冷阈值取热阈值的 1/4）。
         *
         * @param window         中央窗口
         * @param hotThreshold   升热阈值
         * @param clientEstimate 客户端实例数
         * @return 配置
         */
        public static GlobalSpec of(Duration window, long hotThreshold, int clientEstimate) {
            return new GlobalSpec(window, hotThreshold, Math.max(1L, hotThreshold / 4L), 3, 3, clientEstimate);
        }

        /**
         * 校验。
         *
         * @return this
         */
        public GlobalSpec validate() {
            require(window != null && !window.isZero() && !window.isNegative(), "global.window 必须为正");
            require(hotThreshold > 0L, "global.hotThreshold 必须为正");
            require(coolThreshold >= 0L && coolThreshold < hotThreshold,
                    "要求 0 <= coolThreshold < hotThreshold");
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
