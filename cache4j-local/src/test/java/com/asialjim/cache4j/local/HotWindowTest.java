package com.asialjim.cache4j.local;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 滑动窗口计数：分片推进、过期清理、2×windowSlices 的槽位复用。
 *
 * @author Asial Jim
 */
class HotWindowTest {

    private static final int SLICES = 16;

    private static final long WINDOW_NANOS = Duration.ofSeconds(5).toNanos();

    private static final long SLICE_NANOS = WINDOW_NANOS / SLICES;      // 312.5ms

    @Test
    @DisplayName("同一片内多次访问累加到窗口总数")
    void incrementsWithinSameSlice() {
        HotWindow window = new HotWindow(SLICES, WINDOW_NANOS, 0L);
        for (int i = 0; i < 5; i++) {
            window.advanceAndIncrement(SLICE_NANOS / 2);
        }
        assertEquals(5L, window.total(SLICE_NANOS / 2));
    }

    @Test
    @DisplayName("跨越一个窗口后旧计数过期")
    void expiresAfterOneWindow() {
        HotWindow window = new HotWindow(SLICES, WINDOW_NANOS, 0L);
        window.advanceAndIncrement(0L);
        window.advanceAndIncrement(0L);
        assertEquals(2L, window.total(0L));

        // 前进整整一个窗口 → 两片都滑出统计区间
        long later = WINDOW_NANOS + SLICE_NANOS;
        assertEquals(0L, window.total(later));
    }

    @Test
    @DisplayName("滑出窗口的过程中只丢最老的那一片")
    void dropsOldestSliceOnly() {
        HotWindow window = new HotWindow(SLICES, WINDOW_NANOS, 0L);
        // 第 0 片 3 次、第 1 片 2 次
        for (int i = 0; i < 3; i++) {
            window.advanceAndIncrement(0L);
        }
        for (int i = 0; i < 2; i++) {
            window.advanceAndIncrement(SLICE_NANOS);
        }
        assertEquals(5L, window.total(SLICE_NANOS));

        // 再前进 15 片：第 0 片滑出窗口，第 1 片仍在
        long now = SLICE_NANOS * 16L;
        assertEquals(2L, window.total(now));

        // 再前进 1 片：第 1 片也滑出
        assertEquals(0L, window.total(SLICE_NANOS * 17L));
    }

    @Test
    @DisplayName("长时间无访问：一次性全部清零，不会残留旧计数")
    void longIdleResetsAll() {
        HotWindow window = new HotWindow(SLICES, WINDOW_NANOS, 0L);
        for (int i = 0; i < 10; i++) {
            window.advanceAndIncrement(0L);
        }
        assertEquals(10L, window.total(0L));

        // 跳过 100 个窗口
        assertEquals(0L, window.total(WINDOW_NANOS * 100L));
        // 之后重新计数从 1 开始
        assertEquals(1L, window.advanceAndIncrement(WINDOW_NANOS * 100L));
    }

    @Test
    @DisplayName("时钟回退（now 变早）不破坏计数")
    void clockGoingBackwardsIsIgnored() {
        HotWindow window = new HotWindow(SLICES, WINDOW_NANOS, WINDOW_NANOS * 10L);
        window.advanceAndIncrement(WINDOW_NANOS * 10L);
        window.advanceAndIncrement(WINDOW_NANOS * 10L);
        // 回退到更早的时间片：不推进窗口，计数仍然可累加
        assertEquals(3L, window.advanceAndIncrement(WINDOW_NANOS * 9L));
    }
}
