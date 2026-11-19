package com.asialjim.cache4j.core;

import java.util.concurrent.atomic.LongAdder;

/**
 * 基于 {@link LongAdder} 的统计计数器实现。
 *
 * <p>计数项按枚举下标存于数组，避免高并发下的伪共享与大量样板字段；三个 gauge
 * （{@code hotKeyCount} / {@code candidateCount} / {@code estimatedSize}）单独用 volatile 保存，
 * 因为它们表达"当前值"而不是"累计次数"。</p>
 *
 * @author Asial Jim
 */
public final class CacheCounters implements CacheStats {

    /**
     * 可计数的指标。
     */
    public enum Counter {
        REQUEST,
        LOCAL_HIT,
        LOCAL_NEGATIVE_HIT,
        CENTRAL_HIT,
        CENTRAL_NEGATIVE_HIT,
        MISS,
        SOURCE_LOAD,
        SOURCE_NULL,
        LOAD_FAILURE,
        PUT,
        EVICT,
        CLEAR,
        PROMOTION,
        DEMOTION,
        LOCAL_PROMOTION,
        GLOBAL_PROMOTION,
        GLOBAL_DEMOTION,
        DYNAMIC_PUT_DISCARDED,
        HOT_SYNC,
        HOT_SYNC_ERROR,
        HOT_REPORT_DROPPED,
        HOT_COMPENSATION_QUERY,
        VERSION_SYNC,
        VERSION_SYNC_ERROR,
        VERSION_INVALIDATION,
        DEAD_FIELD_DROPPED,
        BACKFILL_DISCARDED,
        STORE_HIT,
        STORE_MISS,
        COMMAND,
        CENTRAL_ERROR,
        CODEC_ERROR
    }

    private final LongAdder[] counters = new LongAdder[Counter.values().length];

    private volatile long hotKeyCount;
    private volatile long candidateCount;
    private volatile long estimatedSize = -1L;

    /**
     * 构造并初始化全部计数器。
     */
    public CacheCounters() {
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
        }
    }

    /**
     * 累加指定计数项。
     *
     * @param counter 计数项
     * @param delta   增量（可为负，用于快照校正场景）
     */
    public void add(Counter counter, long delta) {
        counters[counter.ordinal()].add(delta);
    }

    /**
     * 指定计数项 +1。
     *
     * @param counter 计数项
     */
    public void increment(Counter counter) {
        counters[counter.ordinal()].increment();
    }

    /**
     * 取指定计数项当前值。
     *
     * @param counter 计数项
     * @return 当前值
     */
    public long get(Counter counter) {
        return counters[counter.ordinal()].sum();
    }

    /**
     * @param value 当前热点集合大小
     */
    public void setHotKeyCount(long value) {
        this.hotKeyCount = value;
    }

    /**
     * @param value 当前预选桶大小
     */
    public void setCandidateCount(long value) {
        this.candidateCount = value;
    }

    /**
     * @param value 本地条目数；中央实现传 {@code -1}
     */
    public void setEstimatedSize(long value) {
        this.estimatedSize = value;
    }

    @Override
    public long requestCount() {
        return get(Counter.REQUEST);
    }

    @Override
    public long localHitCount() {
        return get(Counter.LOCAL_HIT);
    }

    @Override
    public long localNegativeHitCount() {
        return get(Counter.LOCAL_NEGATIVE_HIT);
    }

    @Override
    public long centralHitCount() {
        return get(Counter.CENTRAL_HIT);
    }

    @Override
    public long centralNegativeHitCount() {
        return get(Counter.CENTRAL_NEGATIVE_HIT);
    }

    @Override
    public long missCount() {
        return get(Counter.MISS);
    }

    @Override
    public long sourceLoadCount() {
        return get(Counter.SOURCE_LOAD);
    }

    @Override
    public long sourceNullCount() {
        return get(Counter.SOURCE_NULL);
    }

    @Override
    public long loadFailureCount() {
        return get(Counter.LOAD_FAILURE);
    }

    @Override
    public long putCount() {
        return get(Counter.PUT);
    }

    @Override
    public long evictCount() {
        return get(Counter.EVICT);
    }

    @Override
    public long clearCount() {
        return get(Counter.CLEAR);
    }

    @Override
    public long promotionCount() {
        return get(Counter.PROMOTION);
    }

    @Override
    public long demotionCount() {
        return get(Counter.DEMOTION);
    }

    @Override
    public long localPromotionCount() {
        return get(Counter.LOCAL_PROMOTION);
    }

    @Override
    public long globalPromotionCount() {
        return get(Counter.GLOBAL_PROMOTION);
    }

    @Override
    public long globalDemotionCount() {
        return get(Counter.GLOBAL_DEMOTION);
    }

    @Override
    public long dynamicPutDiscarded() {
        return get(Counter.DYNAMIC_PUT_DISCARDED);
    }

    @Override
    public long hotKeyCount() {
        return hotKeyCount;
    }

    @Override
    public long candidateCount() {
        return candidateCount;
    }

    @Override
    public long hotSyncCount() {
        return get(Counter.HOT_SYNC);
    }

    @Override
    public long hotSyncErrorCount() {
        return get(Counter.HOT_SYNC_ERROR);
    }

    @Override
    public long hotReportDropped() {
        return get(Counter.HOT_REPORT_DROPPED);
    }

    @Override
    public long hotCompensationQueryCount() {
        return get(Counter.HOT_COMPENSATION_QUERY);
    }

    @Override
    public long versionSyncCount() {
        return get(Counter.VERSION_SYNC);
    }

    @Override
    public long versionSyncErrorCount() {
        return get(Counter.VERSION_SYNC_ERROR);
    }

    @Override
    public long versionInvalidationCount() {
        return get(Counter.VERSION_INVALIDATION);
    }

    @Override
    public long deadFieldDropped() {
        return get(Counter.DEAD_FIELD_DROPPED);
    }

    @Override
    public long backfillDiscardedCount() {
        return get(Counter.BACKFILL_DISCARDED);
    }

    @Override
    public long storeHitCount() {
        return get(Counter.STORE_HIT);
    }

    @Override
    public long storeMissCount() {
        return get(Counter.STORE_MISS);
    }

    @Override
    public long commandCount() {
        return get(Counter.COMMAND);
    }

    @Override
    public long centralErrorCount() {
        return get(Counter.CENTRAL_ERROR);
    }

    @Override
    public long codecErrorCount() {
        return get(Counter.CODEC_ERROR);
    }

    @Override
    public long estimatedSize() {
        return estimatedSize;
    }
}
