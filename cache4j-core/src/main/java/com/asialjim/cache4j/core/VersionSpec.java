package com.asialjim.cache4j.core;

import java.time.Duration;

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
    
    /**
     * @param message 消息
     * @return 规格异常
     */
    static com.asialjim.cache4j.core.CacheException invalid(String message) {
        return new com.asialjim.cache4j.core.CacheException(
                com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC, message);
    }
}

