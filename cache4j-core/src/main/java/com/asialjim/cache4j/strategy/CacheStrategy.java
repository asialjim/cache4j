package com.asialjim.cache4j.strategy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 缓存策略
 *
 * @param namespace
 * @param localStrategy
 * @param nullAble
 */
public record CacheStrategy(
        // 命名空间
        String namespace,
        Supplier<String> description,
        Supplier<Integer> version,
        // 本地策略函数
        Supplier<LocalStrategy> localStrategy,
        // 缓存快是否允许为空函数
        Supplier<Boolean> nullAble,
        Supplier<Long> localMaxSize,
        Supplier<Long> localTtl,
        Supplier<Integer> localNullTtlDivisor,
        // --- 本地：版本对账
        Supplier<Long> versionCheckInterval,
        Supplier<Long> versionJitter,
        Supplier<Long> versionRetryBackoff,
        Supplier<Integer> reconcileBatchSize,
        Supplier<Integer> reconcilePartitions,
        // --- 本地：热key
        Supplier<Long> hotThresholdQps,
        Supplier<Long> coolQps,
        Supplier<Long> hotWindow,
        Supplier<Integer> hotWindowSlices,
        Supplier<Integer> maxHotKeys,
        Supplier<Long> hotSyncInterval,
        Supplier<Long> hotCooldown,
        Supplier<String> coordination,
        Supplier<Integer> sketchWidthBits,
        Supplier<Integer> sketchDepth,
        Supplier<Integer> hotReportPartitions,
        Supplier<Integer> hotReportBatchMax,
        Supplier<Integer> candidateCapacity,
        Supplier<Long> globalWindow,
        Supplier<Long> globalHotQps,
        Supplier<Long> globalCoolQps,
        Supplier<Integer> globalDemoteWindows,
        Supplier<Integer> globalStaleWindows,
        Supplier<Integer> globalClientEstimate,
        Supplier<Long> centralTtl,
        Supplier<Long> centralNullTtl,
        Supplier<Double> centralTtlJitter,
        Supplier<Boolean> failOpen,
        Supplier<Boolean> allowNoExpiry
) {

    /**
     * 秒 → 毫秒。
     *
     * <p>本类<strong>不含时间单位</strong>：所有时长字段一律是"秒"。
     * 面向运维的单位选择（例如"5 分钟"、"2 小时"）由
     * {@code CacheStrategyAdministrator} 负责换算成秒之后再落到这里。</p>
     */
    public static final long MILLIS_PER_SECOND = 1_000L;

    public CacheStrategy snapshot() {
        CacheStrategyProperty snapshot = new CacheStrategyProperty();
        snapshot.setNamespace(namespace);
        Optional.ofNullable(localStrategy()).map(Supplier::get).map(Enum::name).ifPresent(snapshot::setLocalStrategy);

        //noinspection DuplicatedCode
        snapshot(version,snapshot::setVersion);
        snapshot(description, snapshot::setDescription);
        snapshot(localMaxSize, snapshot::setLocalMaxSize);
        snapshot(localTtl, snapshot::setLocalTtl);
        snapshot(localNullTtlDivisor, snapshot::setLocalNullTtlDivisor);
        snapshot(versionCheckInterval, snapshot::setVersionCheckInterval);
        snapshot(versionJitter, snapshot::setVersionJitter);
        snapshot(versionRetryBackoff, snapshot::setVersionRetryBackoff);
        snapshot(reconcileBatchSize, snapshot::setReconcileBatchSize);
        snapshot(reconcilePartitions, snapshot::setReconcilePartitions);
        snapshot(hotThresholdQps, snapshot::setHotThresholdQps);
        snapshot(coolQps, snapshot::setCoolQps);
        snapshot(hotWindow, snapshot::setHotWindow);
        snapshot(hotWindowSlices, snapshot::setHotWindowSlices);
        snapshot(maxHotKeys, snapshot::setMaxHotKeys);
        snapshot(hotSyncInterval, snapshot::setHotSyncInterval);
        snapshot(hotCooldown, snapshot::setHotCooldown);
        //noinspection DuplicatedCode
        snapshot(coordination, snapshot::setCoordination);
        snapshot(sketchWidthBits, snapshot::setSketchWidthBits);
        snapshot(sketchDepth, snapshot::setSketchDepth);
        snapshot(hotReportPartitions, snapshot::setHotReportPartitions);
        snapshot(hotReportBatchMax, snapshot::setHotReportBatchMax);
        snapshot(candidateCapacity, snapshot::setCandidateCapacity);
        snapshot(globalWindow, snapshot::setGlobalWindow);
        snapshot(globalHotQps, snapshot::setGlobalHotQps);
        snapshot(globalCoolQps, snapshot::setGlobalCoolQps);
        snapshot(globalDemoteWindows, snapshot::setGlobalDemoteWindows);
        snapshot(globalStaleWindows, snapshot::setGlobalStaleWindows);
        snapshot(globalClientEstimate, snapshot::setGlobalClientEstimate);
        snapshot(centralTtl, snapshot::setCentralTtl);
        snapshot(centralNullTtl, snapshot::setCentralNullTtl);
        snapshot(centralTtlJitter, snapshot::setCentralTtlJitter);
        snapshot(failOpen, snapshot::setFailOpen);
        snapshot(allowNoExpiry, snapshot::setAllowNoExpiry);
        return snapshot.cacheStrategy();
    }

    private <V> void snapshot(Supplier<V> supplier, Consumer<V> consumer) {
        Optional.ofNullable(supplier).map(Supplier::get).ifPresent(consumer);
    }

    /**
     * 下一次对账的时刻间隔（毫秒），带随机抖动。
     *
     * <p>抖动的用途是打散"多节点同时到点"：所有节点都是同一个进程启动、读同一份配置，
     * 固定间隔会让它们在同一秒一起打中央，形成周期性尖峰。加上抖动后这些时刻被摊开。</p>
     *
     * <pre>{@code
     * 实际间隔 = versionCheckInterval + random(0, versionJitter]
     * }</pre>
     *
     * <p>抖动只向"更长"的方向偏，不会比配置的间隔更短——否则配 30 秒却可能 25 秒就触发，
     * 与运维的心智模型不符。</p>
     */
    public long reconcileTimeIntervalMillis() {
        long base = this.versionCheckInterval.get() * MILLIS_PER_SECOND;
        Long jitter = this.versionJitter == null ? null : this.versionJitter.get();
        if (jitter != null && jitter > 0) {
            base += ThreadLocalRandom.current().nextLong(0L, jitter * MILLIS_PER_SECOND + 1L);
        }
        return base;
    }

    /**
     * 对账失败后的重试退避（毫秒）。
     *
     * <p>与 {@link #reconcileTimeIntervalMillis()} 分开：正常间隔是"多久核对一次"，
     * 退避是"刚失败过，先别急着再来"。中央故障时若不退避，每次读都会重试，
     * 把一次故障放大成流量风暴。</p>
     */
    public long reconcileRetryBackoffMillis() {
        Long backoff = this.versionRetryBackoff == null ? null : this.versionRetryBackoff.get();
        if (backoff == null || backoff <= 0) {
            return 1_000L;
        }
        return Math.max(1_000L, backoff * MILLIS_PER_SECOND);
    }

    /**
     * 允许空值缓存？
     */
    public static boolean nullable(CacheStrategy cacheStrategy){
        return Optional.ofNullable(cacheStrategy.nullAble()).map(Supplier::get).orElse(false);
    }
}
