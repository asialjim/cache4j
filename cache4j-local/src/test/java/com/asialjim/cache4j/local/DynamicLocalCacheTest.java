package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DYNAMIC} 策略行为测试：升热、冷 key 写丢弃、预选桶、冷却。
 *
 * @author Asial Jim
 */
class DynamicLocalCacheTest {

    private static final String NS = "product:hot";

    private final HashMapLocalCacheTest.MutableClock clock = new HashMapLocalCacheTest.MutableClock();
    private final AtomicInteger reportCalls = new AtomicInteger();
    private final ConcurrentHashMap<CacheKey, HotKeyStatus> statuses = new ConcurrentHashMap<>();

    private CacheKey key(String value) {
        return new CacheKey(NS, "s" + value.length() + ":" + value);
    }

    private LocalCache<String> dynamic(LocalCacheSpec.DynamicSpec dynamic, HotKeyCoordinator coordinator) {
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1), dynamic);
        return LocalCaches.create(spec, coordinator, clock::nanos);
    }

    private HotKeyCoordinator stub() {
        return new HotKeyCoordinator() {
            @Override
            public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
                return reportAndStatusAll(namespace, Map.of(key, delta)).get(key);
            }

            @Override
            public Map<CacheKey, HotKeyReport> reportAndStatusAll(String namespace, Map<CacheKey, Long> deltas) {
                reportCalls.incrementAndGet();
                Map<CacheKey, HotKeyReport> reports = new HashMap<>();
                deltas.forEach((key, delta) -> reports.put(key,
                        new HotKeyReport(statuses.getOrDefault(key, HotKeyStatus.ACTIVE), 0L, 0L, 0L)));
                return reports;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("本地频次达到阈值立即升热，写入被接受")
    void localPromotionAcceptsPut() {
        LocalCache<String> cache = dynamic(LocalCacheSpec.DynamicSpec.defaults(), null);
        CacheKey key = key("hot");

        cache.put(key, LocalEntry.hit("v", 1L), Duration.ofSeconds(10));
        assertEquals(1L, cache.stats().dynamicPutDiscarded(), "冷 key 的写应被丢弃");

        for (int i = 0; i < 5; i++) {
            cache.access(key);
        }
        assertEquals(1L, cache.stats().hotKeyCount());

        cache.put(key, LocalEntry.hit("v2", 2L), Duration.ofSeconds(10));
        assertEquals("v2", cache.entry(key).value());
        assertEquals(1L, cache.stats().localPromotionCount());
    }

    @Test
    @DisplayName("未达阈值的 key 不落本地")
    void coldKeysStayOut() {
        LocalCache<String> cache = dynamic(LocalCacheSpec.DynamicSpec.defaults(), null);
        for (int i = 0; i < 4; i++) {
            assertFalse(cache.access(key("cold")));
        }
        cache.put(key("cold"), LocalEntry.hit("v", 1L), Duration.ofSeconds(10));
        assertEquals(1L, cache.stats().dynamicPutDiscarded());
        assertTrue(cache.keys().isEmpty());
    }

    @Test
    @DisplayName("GLOBAL 模式下，未升热但达到预选阈值的 key 进入候选桶")
    void candidateBucket() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 1_000L, 10);
        LocalCacheSpec.DynamicSpec dynamic = new LocalCacheSpec.DynamicSpec(
                200, Duration.ofSeconds(10), 1_000, Duration.ofSeconds(60), Duration.ofSeconds(1),
                Duration.ofSeconds(30), LocalCacheSpec.Coordination.GLOBAL, global,
                4_096, 4, 16, 64, 1_000);
        LocalCache<String> cache = dynamic(dynamic, stub());

        // U = ceil(1000/10) = 100 < L = 200
        for (int i = 0; i < 120; i++) {
            cache.access(key("warm"));
        }
        assertEquals(0L, cache.stats().hotKeyCount(), "未达本地阈值不应升热");
        assertEquals(1L, cache.stats().candidateCount(), "应进入预选桶");
    }

    @Test
    @DisplayName("LOCAL 模式不维护候选桶")
    void localModeHasNoCandidates() {
        LocalCache<String> cache = dynamic(LocalCacheSpec.DynamicSpec.defaults(), null);
        for (int i = 0; i < 4; i++) {
            cache.access(key("warm"));
        }
        assertEquals(0L, cache.stats().candidateCount());
    }

    @Test
    @DisplayName("一次到点的访问上报整批（当前分区）key，而不是单个 key")
    void hotSyncReportsWholePartition() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100L, 1);
        LocalCacheSpec.DynamicSpec dynamic = LocalCacheSpec.DynamicSpec.defaults().withGlobal(global);
        LocalCache<String> cache = dynamic(dynamic, stub());

        // 先把三个 key 升热
        for (int i = 0; i < 6; i++) {
            cache.access(key("a"));
            cache.access(key("b"));
            cache.access(key("c"));
        }
        assertEquals(3L, cache.stats().hotKeyCount());

        // 第一次热同步只设点
        cache.hotSync(key("a"));
        assertEquals(0, reportCalls.get());

        // 到点后一次上报该分区的全部 key
        clock.advance(Duration.ofSeconds(2));
        cache.hotSync(key("a"));
        assertTrue(reportCalls.get() >= 1, "到点后应产生一次批量上报");
    }

    @Test
    @DisplayName("中央判定 COOLING 后降热，并进入冷却期阻止立即重新升热")
    void demotionStartsCooldown() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100L, 1);
        LocalCacheSpec.DynamicSpec dynamic = LocalCacheSpec.DynamicSpec.defaults().withGlobal(global);
        LocalCache<String> cache = dynamic(dynamic, stub());
        CacheKey key = key("a");

        for (int i = 0; i < 6; i++) {
            cache.access(key);
        }
        assertEquals(1L, cache.stats().hotKeyCount());

        // 让中央返回 COOLING
        statuses.put(key, HotKeyStatus.COOLING);
        cache.hotSync(key);
        clock.advance(Duration.ofSeconds(2));
        cache.hotSync(key);

        assertEquals(0L, cache.stats().hotKeyCount(), "收到 COOLING 后应降热");
        assertEquals(1L, cache.stats().globalDemotionCount());

        // 冷却期内即使频次达标也不允许升热
        for (int i = 0; i < 10; i++) {
            cache.access(key);
        }
        assertEquals(0L, cache.stats().hotKeyCount(), "冷却期内不得重新升热");
    }

    @Test
    @DisplayName("GLOBAL 模式缺少协调器时工厂直接报错（不静默退化）")
    void globalWithoutCoordinatorFails() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100L, 1);
        LocalCacheSpec.DynamicSpec dynamic = LocalCacheSpec.DynamicSpec.defaults().withGlobal(global);
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1), dynamic);
        assertThrows(com.asialjim.cache4j.core.CacheException.class,
                () -> LocalCaches.create(spec, null, clock::nanos));
    }
}
