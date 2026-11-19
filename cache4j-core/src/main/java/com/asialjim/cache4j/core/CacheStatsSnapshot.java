package com.asialjim.cache4j.core;

/**
 * 不可变统计快照，便于打点、日志与测试断言。
 *
 * <p>派生量按需计算，不在构造时缓存，避免两处口径漂移。</p>
 *
 * @author Asial Jim
 */
public record CacheStatsSnapshot(
        long requestCount,
        long localHitCount,
        long localNegativeHitCount,
        long centralHitCount,
        long centralNegativeHitCount,
        long missCount,
        long sourceLoadCount,
        long sourceNullCount,
        long loadFailureCount,
        long putCount,
        long evictCount,
        long clearCount,
        long promotionCount,
        long demotionCount,
        long localPromotionCount,
        long globalPromotionCount,
        long globalDemotionCount,
        long dynamicPutDiscarded,
        long hotKeyCount,
        long candidateCount,
        long hotSyncCount,
        long hotSyncErrorCount,
        long hotReportDropped,
        long hotCompensationQueryCount,
        long versionSyncCount,
        long versionSyncErrorCount,
        long versionInvalidationCount,
        long deadFieldDropped,
        long backfillDiscardedCount,
        long storeHitCount,
        long storeMissCount,
        long commandCount,
        long centralErrorCount,
        long codecErrorCount,
        long estimatedSize
) {

    /**
     * @return 端到端下游保护率；无样本返回 {@code -1}
     */
    public double protectionRate() {
        return requestCount == 0 ? -1D : 1D - (double) missCount / requestCount;
    }

    /**
     * @return L1 对 L2 的保护率；无样本返回 {@code -1}
     */
    public double localProtectionRate() {
        return requestCount == 0 ? -1D : (double) (localHitCount + localNegativeHitCount) / requestCount;
    }

    /**
     * @return L2 对数据源的条件保护率；无样本返回 {@code -1}
     */
    public double centralProtectionRate() {
        long touched = requestCount - localHitCount - localNegativeHitCount;
        return touched <= 0 ? -1D : (double) (centralHitCount + centralNegativeHitCount) / touched;
    }

    /**
     * @return 值命中率；无样本返回 {@code -1}
     */
    public double valueHitRate() {
        return requestCount == 0 ? -1D : (double) (localHitCount + centralHitCount) / requestCount;
    }

    /**
     * @return 确定性结论里的空值占比；无样本返回 {@code -1}
     */
    public double negativeRatio() {
        long hit = localHitCount + centralHitCount;
        long neg = localNegativeHitCount + centralNegativeHitCount;
        return hit + neg == 0 ? -1D : (double) neg / (hit + neg);
    }

    /**
     * @return 回源里白跑的比例；无样本返回 {@code -1}
     */
    public double nullLoadRatio() {
        return sourceLoadCount == 0 ? -1D : (double) sourceNullCount / sourceLoadCount;
    }
}
