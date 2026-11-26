package com.asialjim.cache4j.core;

import java.time.Duration;

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
        public DynamicSpec validate(long maxSize) {
            require(window != null && !window.isZero() && !window.isNegative(), "dynamic.window 必须为正");
            require(windowSlices >= 2, "dynamic.windowSlices 必须 >= 2：" + windowSlices);
            require(window.toMillis() / windowSlices >= 1L,
                    "window / windowSlices 必须 >= 1ms（否则时间片失去分辨率）：window=" + window
                            + "，slices=" + windowSlices);
            require(maxHotKeys >= 1 && maxHotKeys <= maxSize,
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
    
    /**
     * @param message 消息
     * @return 规格异常
     */
    static com.asialjim.cache4j.core.CacheException invalid(String message) {
        return new com.asialjim.cache4j.core.CacheException(
                com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC, message);
    }
}

