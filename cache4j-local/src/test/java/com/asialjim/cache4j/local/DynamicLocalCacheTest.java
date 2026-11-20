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

    /**
     * 测试用低阈值参数：1 QPS × 1s 窗口 = 1 次即升热，等价于旧语义的"几次访问就热"。
     *
     * @return 参数
     */
    private static LocalCacheSpec.DynamicSpec lowThreshold() {
        LocalCacheSpec.DynamicSpec d = LocalCacheSpec.DynamicSpec.defaults();
        return new LocalCacheSpec.DynamicSpec(1, 0, Duration.ofSeconds(1), 16, 1_000,
                d.syncInterval(), d.cooldown(), d.coordination(), d.global(),
                d.sketchWidthBits(), d.sketchDepth(), d.hotReportPartitions(),
                d.hotReportBatchMax(), d.candidateCapacity());
    }

    /**
     * 测试用高阈值参数：100 QPS × 1s 窗口 = 100 次才升热，用于验证"少量访问不升热"。
     *
     * @return 参数
     */
    private static LocalCacheSpec.DynamicSpec highThreshold() {
        LocalCacheSpec.DynamicSpec d = LocalCacheSpec.DynamicSpec.defaults();
        return new LocalCacheSpec.DynamicSpec(100, 25, Duration.ofSeconds(1), 16, 1_000,
                d.syncInterval(), d.cooldown(), d.coordination(), d.global(),
                d.sketchWidthBits(), d.sketchDepth(), d.hotReportPartitions(),
                d.hotReportBatchMax(), d.candidateCapacity());
    }

    /**
     * 测试用 GLOBAL 低阈值参数：2 QPS × 1s 窗口 = 2 次即升热（GLOBAL 要求 hotThresholdQps >= 2）。
     *
     * @param global 中央阈值
     * @return 参数
     */
    private static LocalCacheSpec.DynamicSpec globalThreshold(LocalCacheSpec.GlobalSpec global) {
        LocalCacheSpec.DynamicSpec d = LocalCacheSpec.DynamicSpec.defaults();
        return new LocalCacheSpec.DynamicSpec(2, 1, Duration.ofSeconds(1), 16, 1_000,
                d.syncInterval(), d.cooldown(), LocalCacheSpec.Coordination.GLOBAL, global,
                d.sketchWidthBits(), d.sketchDepth(), d.hotReportPartitions(),
                d.hotReportBatchMax(), d.candidateCapacity());
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
        LocalCache<String> cache = dynamic(lowThreshold(), null);
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
    @DisplayName("accepts：只有已升热的 key 才会被本地接受写入")
    void acceptsOnlyHotKeys() {
        LocalCache<String> cache = dynamic(lowThreshold(), null);
        CacheKey hot = key("hot");

        assertFalse(cache.accepts(hot), "冷 key 不应被接受");
        for (int i = 0; i < 5; i++) {
            cache.access(hot);
        }
        assertTrue(cache.accepts(hot), "升热后应被接受");
    }

    @Test
    @DisplayName("未达阈值的 key 不落本地")
    void coldKeysStayOut() {
        LocalCache<String> cache = dynamic(highThreshold(), null);
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
        // 候选阈值 = min(ceil(10 QPS / 1), 199) = 10 QPS → 10s 窗口内 100 次；升热阈值 200 QPS → 2000 次
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 10L, 1);
        LocalCacheSpec.DynamicSpec dynamic = new LocalCacheSpec.DynamicSpec(
                200, 50, Duration.ofSeconds(10), 16, 1_000, Duration.ofSeconds(1),
                Duration.ofSeconds(30), LocalCacheSpec.Coordination.GLOBAL, global,
                12, 4, 16, 16, 1_000);
        LocalCache<String> cache = dynamic(dynamic, stub());

        // 候选阈值 = ceil(globalHotQps / clientEstimate)，被 hotThresholdQps-1 夹住
        // 前 100 次由 CMS 粗筛（达到候选阈值）后才建立精确窗口；窗口从建表时刻开始计数，
        // 因此还需要再攒 100 次才进候选桶——远低于升热阈值（2000 次）
        for (int i = 0; i < 300; i++) {
            cache.access(key("warm"));
        }
        assertEquals(0L, cache.stats().hotKeyCount(), "未达本地阈值不应升热");
        assertEquals(1L, cache.stats().candidateCount(), "应进入预选桶");
    }

    @Test
    @DisplayName("LOCAL 模式不维护候选桶")
    void localModeHasNoCandidates() {
        LocalCache<String> cache = dynamic(lowThreshold(), null);
        for (int i = 0; i < 4; i++) {
            cache.access(key("warm"));
        }
        assertEquals(0L, cache.stats().candidateCount());
    }

    @Test
    @DisplayName("一次到点的访问上报整批（当前分区）key，而不是单个 key")
    void hotSyncReportsWholePartition() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100L, 1);
        LocalCacheSpec.DynamicSpec dynamic = globalThreshold(global);
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
        LocalCacheSpec.DynamicSpec dynamic = globalThreshold(global);
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
        LocalCacheSpec.DynamicSpec dynamic = globalThreshold(global);
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1), dynamic);
        assertThrows(com.asialjim.cache4j.core.CacheException.class,
                () -> LocalCaches.create(spec, null, clock::nanos));
    }
}
