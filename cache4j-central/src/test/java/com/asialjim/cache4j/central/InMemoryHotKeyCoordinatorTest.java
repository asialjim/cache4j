package com.asialjim.cache4j.central;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内存热 key 协调器语义测试。
 *
 * @author Asial Jim
 */
class InMemoryHotKeyCoordinatorTest {

    private static final String NS = "product:hot";
    private static final Duration WINDOW = Duration.ofSeconds(10);

    /**
     * 测试用阈值：QPS 口径，{@code hotCount = hotQps × 窗口秒数}。
     * 取 {@code hotQps=10} 使 10s 窗口内的阈值落在 100 次，便于构造上报量。
     */
    private static final long HOT_QPS = 10L;

    private final InMemoryCentralCacheTest.MutableClock clock = new InMemoryCentralCacheTest.MutableClock();

    private InMemoryHotKeyCoordinator coordinator(GlobalSpec global) {
        return new InMemoryHotKeyCoordinator(namespace -> NS.equals(namespace) ? global : null,
                clock::millis);
    }

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("达到全局阈值判 HOT")
    void shouldBecomeHot() {
        InMemoryHotKeyCoordinator coordinator = coordinator(
                GlobalSpec.of(WINDOW, 10L, 1));
        HotKeyReport report = coordinator.reportAndStatus(NS, key("a"), 120L);
        assertEquals(HotKeyStatus.HOT, report.status(), "只回传状态，不回传计数（见 HotKeyReport 契约）");
    }

    @Test
    @DisplayName("有上报但未达阈值判 ACTIVE")
    void shouldStayActive() {
        InMemoryHotKeyCoordinator coordinator = coordinator(
                GlobalSpec.of(WINDOW, 10L, 1));
        HotKeyReport report = coordinator.reportAndStatus(NS, key("a"), 10L);
        assertEquals(HotKeyStatus.ACTIVE, report.status());
    }

    @Test
    @DisplayName("从未上报且本次 delta=0 → UNKNOWN")
    void unknownWhenNeverSeen() {
        InMemoryHotKeyCoordinator coordinator = coordinator(
                GlobalSpec.of(WINDOW, 10L, 1));
        assertEquals(HotKeyStatus.UNKNOWN, coordinator.reportAndStatus(NS, key("a"), 0L).status());
    }

    @Test
    @DisplayName("长时间无上报判 COOLING")
    void staleShouldCool() {
        GlobalSpec global = new GlobalSpec(WINDOW, 10L, 3L, 3, 2, 1);
        InMemoryHotKeyCoordinator coordinator = coordinator(global);
        assertEquals(HotKeyStatus.HOT, coordinator.reportAndStatus(NS, key("a"), 200L).status());

        // 两个窗口内没有上报 → staleWindows=2 触发 COOLING
        clock.advance(WINDOW.multipliedBy(2));
        assertEquals(HotKeyStatus.COOLING, coordinator.reportAndStatus(NS, key("a"), 0L).status());
    }

    @Test
    @DisplayName("连续低活动达 demoteWindows 判 COOLING（带滞回）")
    void lowActivityShouldCoolAfterDemoteWindows() {
        GlobalSpec global = new GlobalSpec(WINDOW, 10L, 1L, 2, 100, 1);
        InMemoryHotKeyCoordinator coordinator = coordinator(global);
        assertEquals(HotKeyStatus.HOT, coordinator.reportAndStatus(NS, key("a"), 200L).status());

        // 下一个窗口：合计 = 当前 0 + 上一窗口 200，仍然 HOT
        clock.advance(WINDOW);
        assertEquals(HotKeyStatus.HOT, coordinator.reportAndStatus(NS, key("a"), 0L).status());

        // 再下一个窗口：两个窗口都是 0 → 开始计 lowSince，尚未到 demoteWindows
        clock.advance(WINDOW);
        assertEquals(HotKeyStatus.ACTIVE, coordinator.reportAndStatus(NS, key("a"), 0L).status());

        // 连续第二个低活动窗口 → COOLING
        clock.advance(WINDOW);
        assertEquals(HotKeyStatus.COOLING, coordinator.reportAndStatus(NS, key("a"), 0L).status());
    }

    @Test
    @DisplayName("批量上报逐 key 返回结果")
    void batchReport() {
        InMemoryHotKeyCoordinator coordinator = coordinator(
                GlobalSpec.of(WINDOW, 10L, 1));
        Map<CacheKey, HotKeyReport> reports = coordinator.reportAndStatusAll(NS,
                Map.of(key("hot"), 120L, key("cold"), 1L));
        assertEquals(HotKeyStatus.HOT, reports.get(key("hot")).status());
        assertEquals(HotKeyStatus.ACTIVE, reports.get(key("cold")).status());
    }

    @Test
    @DisplayName("未配置 global 的 namespace 不猜阈值，返回 UNKNOWN")
    void missingGlobalSpecIsUnknown() {
        InMemoryHotKeyCoordinator coordinator = coordinator(
                GlobalSpec.of(WINDOW, 10L, 1));
        assertEquals(HotKeyStatus.UNKNOWN,
                coordinator.reportAndStatus("other:ns", new CacheKey("other:ns", "s1:a"), 999L).status());
    }
}
