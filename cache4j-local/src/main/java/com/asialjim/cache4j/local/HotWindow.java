package com.asialjim.cache4j.local;

import java.util.Arrays;

/**
 * 单个 key 的滑动窗口计数：把 {@code window} 切成 {@code windowSlices} 个时间片，
 * 循环数组保存最近若干片的计数，判定时累加"当前片 + 前 windowSlices-1 片"。
 *
 * <p>结构照抄 JD HotKey 的 {@code SlidingWindow}：<strong>数组长度取 2 × windowSlices</strong>，
 * 这样每前进一片只清理"新片对应的槽"，清理区间与统计区间永不重叠，访问路径平摊 O(1)。</p>
 *
 * <p>与共享的 Count-Min Sketch 分工：<strong>本类做精确判定</strong>（升热），CMS 只做粗筛
 * （决定"谁值得建这个窗口"）。CMS 只会高估，适合"宁多勿少"的候选阶段；
 * 升热会真实占用本地内存，必须精确。</p>
 *
 * @author Asial Jim
 */
final class HotWindow {

    private final int[] slices;

    private final int windowSlices;

    private final long sliceNanos;

    private long currentSliceId;

    /**
     * @param windowSlices 时间片数（≥ 2）
     * @param windowNanos  窗口总长度（纳秒）
     * @param nowNanos     当前单调时钟
     */
    HotWindow(int windowSlices, long windowNanos, long nowNanos) {
        this(windowSlices, windowNanos, nowNanos, 0);
    }

    /**
     * @param windowSlices 时间片数（≥ 2）
     * @param windowNanos  窗口总长度（纳秒）
     * @param nowNanos     当前单调时钟
     * @param initialCount 建表时的初始计数（见 {@code DynamicLocalCache#preciseCount}：
     *                     用粗筛阈值做种，避免丢掉"建立精确统计之前"已经攒下的访问量）
     */
    HotWindow(int windowSlices, long windowNanos, long nowNanos, int initialCount) {
        this.windowSlices = windowSlices;
        this.slices = new int[windowSlices * 2];
        this.sliceNanos = Math.max(1L, windowNanos / windowSlices);
        this.currentSliceId = nowNanos / sliceNanos;
        if (initialCount > 0) {
            this.slices[slotOf(currentSliceId)] = initialCount;
        }
    }

    /**
     * 记录一次访问并返回窗口内累计次数。
     *
     * @param nowNanos 当前单调时钟
     * @return 最近 {@code windowSlices} 片的计数之和
     */
    synchronized long advanceAndIncrement(long nowNanos) {
        advanceTo(nowNanos / sliceNanos);
        slices[slotOf(currentSliceId)]++;
        return sum();
    }

    /**
     * 只读累计次数（会顺手推进窗口）。
     *
     * @param nowNanos 当前单调时钟
     * @return 最近 {@code windowSlices} 片的计数之和
     */
    synchronized long total(long nowNanos) {
        advanceTo(nowNanos / sliceNanos);
        return sum();
    }

    private long sum() {
        long total = 0L;
        for (int i = 0; i < windowSlices; i++) {
            total += slices[slotOf(currentSliceId - i)];
        }
        return total;
    }

    /**
     * 把窗口推进到指定片，并清掉这期间滑过期的槽。
     *
     * @param sliceId 目标片编号
     */
    private void advanceTo(long sliceId) {
        long elapsed = sliceId - currentSliceId;
        if (elapsed <= 0L) {
            return;
        }
        if (elapsed >= slices.length) {
            // 超过两倍窗口：全部过期
            Arrays.fill(slices, 0);
        } else {
            // 逐片清理"新片要用的槽"——该槽上一次使用在 2×windowSlices 片之前
            for (long i = currentSliceId + 1L; i <= sliceId; i++) {
                slices[slotOf(i)] = 0;
            }
        }
        currentSliceId = sliceId;
    }

    private int slotOf(long sliceId) {
        long mod = sliceId % slices.length;
        return (int) (mod < 0L ? mod + slices.length : mod);
    }
}
