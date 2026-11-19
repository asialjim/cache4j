package com.asialjim.cache4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径测试：不变量 + 恒等式 + noop 语义。
 *
 * @author Asial Jim
 */
class CacheStatsTest {

    @Test
    @DisplayName("四格漏斗之和等于请求数（不变量）")
    void funnelShouldSum() {
        CacheCounters counters = new CacheCounters();
        counters.add(CacheCounters.Counter.REQUEST, 10L);
        counters.add(CacheCounters.Counter.LOCAL_HIT, 3L);
        counters.add(CacheCounters.Counter.LOCAL_NEGATIVE_HIT, 1L);
        counters.add(CacheCounters.Counter.CENTRAL_HIT, 4L);
        counters.add(CacheCounters.Counter.CENTRAL_NEGATIVE_HIT, 1L);
        counters.add(CacheCounters.Counter.MISS, 1L);

        long sum = counters.localHitCount() + counters.localNegativeHitCount()
                + counters.centralHitCount() + counters.centralNegativeHitCount() + counters.missCount();
        assertEquals(counters.requestCount(), sum);
    }

    @Test
    @DisplayName("三层保护率满足乘积恒等式")
    void protectionRatesShouldMultiply() {
        CacheCounters counters = new CacheCounters();
        counters.add(CacheCounters.Counter.REQUEST, 100L);
        counters.add(CacheCounters.Counter.LOCAL_HIT, 50L);
        counters.add(CacheCounters.Counter.LOCAL_NEGATIVE_HIT, 10L);
        counters.add(CacheCounters.Counter.CENTRAL_HIT, 20L);
        counters.add(CacheCounters.Counter.CENTRAL_NEGATIVE_HIT, 5L);
        counters.add(CacheCounters.Counter.MISS, 15L);

        double endToEnd = counters.protectionRate();
        double l1 = counters.localProtectionRate();
        double l2 = counters.centralProtectionRate();
        assertEquals(1D - endToEnd, (1D - l1) * (1D - l2), 1e-9);
        assertEquals(1D - 15D / 100D, endToEnd, 1e-9);
        // 值命中率只算真值：(localHit + centralHit) / request
        assertEquals(0.7D, counters.valueHitRate(), 1e-9);
        // 确定性结论里空值占比：neg / (hit + neg) = 15 / 85
        assertEquals(15D / 85D, counters.negativeRatio(), 1e-9);
    }

    @Test
    @DisplayName("无样本时派生量返回 -1，而不是 0")
    void emptyStatsShouldReturnMinusOne() {
        CacheCounters counters = new CacheCounters();
        assertEquals(-1D, counters.protectionRate());
        assertEquals(-1D, counters.valueHitRate());
        assertEquals(-1D, counters.nullLoadRatio());
        assertEquals(-1L, counters.estimatedSize());
    }

    @Test
    @DisplayName("nullTtl=0 时负命中恒为 0，两个口径相等")
    void withoutNullCachingRatesCoincide() {
        CacheCounters counters = new CacheCounters();
        counters.add(CacheCounters.Counter.REQUEST, 10L);
        counters.add(CacheCounters.Counter.CENTRAL_HIT, 6L);
        counters.add(CacheCounters.Counter.MISS, 4L);
        assertEquals(0L, counters.localNegativeHitCount() + counters.centralNegativeHitCount());
        assertEquals(counters.protectionRate(), counters.valueHitRate(), 1e-9);
    }

    @Test
    @DisplayName("穿透量化：sourceNull / sourceLoad")
    void nullLoadRatio() {
        CacheCounters counters = new CacheCounters();
        counters.add(CacheCounters.Counter.SOURCE_LOAD, 8L);
        counters.add(CacheCounters.Counter.SOURCE_NULL, 6L);
        assertEquals(0.75D, counters.nullLoadRatio(), 1e-9);
    }

    @Test
    @DisplayName("关闭统计时计数为 0、派生量为 -1")
    void noopStats() {
        CacheStats stats = CacheStats.noop();
        stats.snapshot();
        assertEquals(0L, stats.requestCount());
        assertEquals(-1L, stats.estimatedSize());
        assertEquals(-1D, stats.protectionRate());
        assertTrue(Double.isNaN(stats.snapshot().valueHitRate()) || stats.snapshot().valueHitRate() == -1D);
    }
}
