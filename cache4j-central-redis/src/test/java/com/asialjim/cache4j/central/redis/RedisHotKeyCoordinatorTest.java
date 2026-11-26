package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.CacheStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 热 key 协调器测试：阈值解析 + 状态机委托。
 *
 * @author Asial Jim
 */
class RedisHotKeyCoordinatorTest {

    private static final String NS = "product:hot";
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final RedisCentralCacheTest.MutableClock clock = new RedisCentralCacheTest.MutableClock();

    private RedisHotKeyCoordinator coordinator(GlobalSpec global) {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(
                namespace -> NS.equals(namespace) ? global : null, clock::millis);
        return new RedisHotKeyCoordinator(store, namespace -> NS.equals(namespace) ? global : null);
    }

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    @Test
    @DisplayName("达到全局阈值判 HOT")
    void becomesHot() {
        RedisHotKeyCoordinator coordinator = coordinator(GlobalSpec.of(WINDOW, 10L, 1));
        HotKeyReport report = coordinator.reportAndStatus(NS, key("a"), 120L);
        assertEquals(HotKeyStatus.HOT, report.status());

    }

    @Test
    @DisplayName("未达阈值判 ACTIVE")
    void staysActive() {
        RedisHotKeyCoordinator coordinator = coordinator(GlobalSpec.of(WINDOW, 10L, 1));
        assertEquals(HotKeyStatus.ACTIVE, coordinator.reportAndStatus(NS, key("a"), 10L).status());
    }

    @Test
    @DisplayName("从未见过且 delta=0 → UNKNOWN")
    void unknownWhenNeverSeen() {
        RedisHotKeyCoordinator coordinator = coordinator(GlobalSpec.of(WINDOW, 10L, 1));
        assertEquals(HotKeyStatus.UNKNOWN, coordinator.reportAndStatus(NS, key("a"), 0L).status());
    }

    @Test
    @DisplayName("未配置 dynamic.global 的 namespace 不猜阈值")
    void missingGlobalSpecIsUnknown() {
        RedisHotKeyCoordinator coordinator = coordinator(GlobalSpec.of(WINDOW, 10L, 1));
        CacheKey other = new CacheKey("other:ns", "s1:a");
        assertEquals(HotKeyStatus.UNKNOWN, coordinator.reportAndStatus("other:ns", other, 999L).status());

        Map<CacheKey, HotKeyReport> batch = coordinator.reportAndStatusAll(
                "other:ns", Map.of(other, 999L));
        assertEquals(HotKeyStatus.UNKNOWN, batch.get(other).status());
    }

    @Test
    @DisplayName("批量上报逐 key 返回结果")
    void batchReport() {
        RedisHotKeyCoordinator coordinator = coordinator(GlobalSpec.of(WINDOW, 10L, 1));
        Map<CacheKey, HotKeyReport> reports = coordinator.reportAndStatusAll(NS,
                Map.of(key("hot"), 120L, key("cold"), 1L));
        assertEquals(HotKeyStatus.HOT, reports.get(key("hot")).status());
        assertEquals(HotKeyStatus.ACTIVE, reports.get(key("cold")).status());
    }

    @Test
    @DisplayName("长时间无上报判 COOLING")
    void staleCoolsDown() {
        GlobalSpec global = new GlobalSpec(WINDOW, 10L, 3L, 3, 2, 1);
        RedisHotKeyCoordinator coordinator = coordinator(global);
        assertEquals(HotKeyStatus.HOT, coordinator.reportAndStatus(NS, key("a"), 200L).status());

        clock.advance(WINDOW.multipliedBy(2));
        assertEquals(HotKeyStatus.COOLING, coordinator.reportAndStatus(NS, key("a"), 0L).status());
    }
}
