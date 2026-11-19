package com.asialjim.cache4j.central;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 内存热 key 协调器：单机 / 测试用，语义与 Redis 实现保持一致。
 *
 * <p>中央按 {@code windowId = now / global.window} 维护"当前 + 上一窗口"的合计频次，并返回：</p>
 * <ul>
 *   <li>{@link HotKeyStatus#HOT}：合计 ≥ {@code hotThreshold}；</li>
 *   <li>{@link HotKeyStatus#ACTIVE}：有上报但未达阈值；</li>
 *   <li>{@link HotKeyStatus#COOLING}：连续 {@code demoteWindows} 个窗口低于 {@code coolThreshold}，
 *       或超过 {@code staleWindows} 个窗口无上报；</li>
 *   <li>{@link HotKeyStatus#UNKNOWN}：本次 {@code delta == 0} 且中央从未见过该 key。</li>
 * </ul>
 *
 * <p>未配置 {@code dynamic.global} 的 namespace 返回 {@link HotKeyStatus#UNKNOWN}（不猜阈值）。</p>
 *
 * @author Asial Jim
 */
public final class InMemoryHotKeyCoordinator implements HotKeyCoordinator {

    private final Function<String, LocalCacheSpec.GlobalSpec> globalSpecs;
    private final LongSupplier milliClock;
    private final ConcurrentHashMap<String, ConcurrentHashMap<CacheKey, Window>> state = new ConcurrentHashMap<>();

    /**
     * @param globalSpecs namespace → 中央阈值解析器
     * @param milliClock  毫秒时钟
     */
    public InMemoryHotKeyCoordinator(Function<String, LocalCacheSpec.GlobalSpec> globalSpecs,
                                     LongSupplier milliClock) {
        this.globalSpecs = globalSpecs;
        this.milliClock = milliClock;
    }

    @Override
    public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
        LocalCacheSpec.GlobalSpec globalSpec = globalSpecs.apply(namespace);
        if (globalSpec == null || globalSpec.window() == null || globalSpec.window().isZero()) {
            return HotKeyReport.unknown();
        }
        long windowMillis = globalSpec.window().toMillis();
        long windowId = milliClock.getAsLong() / windowMillis;
        Window window = state.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            window.rotateTo(windowId);
            if (delta > 0L) {
                window.current += delta;
                window.lastActiveWindow = windowId;
            }
            long total = window.current + window.previous;

            if (delta == 0L && window.lastActiveWindow < 0L && total == 0L) {
                return new HotKeyReport(HotKeyStatus.UNKNOWN, 0L, -1L, -1L);
            }

            HotKeyStatus status;
            if (window.lastActiveWindow >= 0L
                    && windowId - window.lastActiveWindow >= globalSpec.staleWindows()) {
                status = HotKeyStatus.COOLING;
                window.lowSince = -1L;
            } else if (total >= globalSpec.hotThreshold()) {
                status = HotKeyStatus.HOT;
                window.lastHotWindow = windowId;
                window.lowSince = -1L;
            } else if (total < globalSpec.coolThreshold()) {
                if (window.lowSince < 0L) {
                    window.lowSince = windowId;
                }
                status = windowId - window.lowSince + 1L >= globalSpec.demoteWindows()
                        ? HotKeyStatus.COOLING : HotKeyStatus.ACTIVE;
            } else {
                window.lowSince = -1L;
                status = HotKeyStatus.ACTIVE;
            }
            return new HotKeyReport(status, total, window.lastActiveWindow, window.lastHotWindow);
        }
    }

    @Override
    public Map<CacheKey, HotKeyReport> reportAndStatusAll(String namespace, Map<CacheKey, Long> deltas) {
        Map<CacheKey, HotKeyReport> result = new HashMap<>(deltas.size() * 2);
        deltas.forEach((key, delta) -> result.put(key, reportAndStatus(namespace, key, delta)));
        return result;
    }

    @Override
    public void close() {
        state.clear();
    }

    /**
     * 单个 key 的双窗口计数。
     */
    private static final class Window {

        private long windowId = Long.MIN_VALUE;
        private long current;
        private long previous;
        private long lastActiveWindow = -1L;
        private long lastHotWindow = -1L;
        private long lowSince = -1L;

        void rotateTo(long target) {
            if (windowId == target) {
                return;
            }
            previous = windowId == target - 1L ? current : 0L;
            current = 0L;
            windowId = target;
        }
    }
}
