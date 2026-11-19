package com.asialjim.cache4j.core;

/**
 * 未启用统计时的空实现。
 *
 * <p>刻意做成独立类型而不是在热路径上加 {@code if} 分支：统计关闭时所有调用变成空方法，
 * 开销接近零。派生量返回 {@code -1} 以区分"未统计"与"真的全 miss"。</p>
 *
 * @author Asial Jim
 */
final class NoopStats implements CacheStats {

    static final NoopStats INSTANCE = new NoopStats();

    private NoopStats() {
    }

    @Override
    public long requestCount() {
        return 0L;
    }

    @Override
    public long localHitCount() {
        return 0L;
    }

    @Override
    public long localNegativeHitCount() {
        return 0L;
    }

    @Override
    public long centralHitCount() {
        return 0L;
    }

    @Override
    public long centralNegativeHitCount() {
        return 0L;
    }

    @Override
    public long missCount() {
        return 0L;
    }

    @Override
    public long sourceLoadCount() {
        return 0L;
    }

    @Override
    public long sourceNullCount() {
        return 0L;
    }

    @Override
    public long loadFailureCount() {
        return 0L;
    }

    @Override
    public long putCount() {
        return 0L;
    }

    @Override
    public long evictCount() {
        return 0L;
    }

    @Override
    public long clearCount() {
        return 0L;
    }

    @Override
    public long promotionCount() {
        return 0L;
    }

    @Override
    public long demotionCount() {
        return 0L;
    }

    @Override
    public long localPromotionCount() {
        return 0L;
    }

    @Override
    public long globalPromotionCount() {
        return 0L;
    }

    @Override
    public long globalDemotionCount() {
        return 0L;
    }

    @Override
    public long dynamicPutDiscarded() {
        return 0L;
    }

    @Override
    public long hotKeyCount() {
        return 0L;
    }

    @Override
    public long candidateCount() {
        return 0L;
    }

    @Override
    public long hotSyncCount() {
        return 0L;
    }

    @Override
    public long hotSyncErrorCount() {
        return 0L;
    }

    @Override
    public long hotReportDropped() {
        return 0L;
    }

    @Override
    public long hotCompensationQueryCount() {
        return 0L;
    }

    @Override
    public long versionSyncCount() {
        return 0L;
    }

    @Override
    public long versionSyncErrorCount() {
        return 0L;
    }

    @Override
    public long versionInvalidationCount() {
        return 0L;
    }

    @Override
    public long deadFieldDropped() {
        return 0L;
    }

    @Override
    public long backfillDiscardedCount() {
        return 0L;
    }

    @Override
    public long storeHitCount() {
        return 0L;
    }

    @Override
    public long storeMissCount() {
        return 0L;
    }

    @Override
    public long commandCount() {
        return 0L;
    }

    @Override
    public long centralErrorCount() {
        return 0L;
    }

    @Override
    public long codecErrorCount() {
        return 0L;
    }

    @Override
    public long estimatedSize() {
        return -1L;
    }
}
