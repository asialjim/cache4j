package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地配置与预选阈值测试。
 *
 * @author Asial Jim
 */
class LocalCacheSpecTest {

    @Test
    @DisplayName("STATIC 基础校验")
    void staticValidation() {
        LocalCacheSpec.staticCache("user:detail", 100, Duration.ofSeconds(30)).validate();
        assertThrows(CacheException.class,
                () -> LocalCacheSpec.staticCache("user:detail", 0, Duration.ofSeconds(30)));
        assertThrows(CacheException.class,
                () -> LocalCacheSpec.staticCache("user:detail", 100, Duration.ZERO));
        assertThrows(CacheException.class,
                () -> LocalCacheSpec.staticCache("user:detail", 100, Duration.ofSeconds(30),
                        new LocalCacheSpec.VersionSpec(Duration.ZERO, Duration.ofMillis(10), Duration.ofMillis(200), 1000))
                        .validate());
    }

    @Test
    @DisplayName("DYNAMIC 默认参数可用")
    void dynamicDefaultsValid() {
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache("product:hot", 10_000,
                Duration.ofSeconds(60), LocalCacheSpec.DynamicSpec.defaults());
        spec.validate();
        assertEquals(LocalStrategy.DYNAMIC, spec.strategy());
    }

    @Test
    @DisplayName("DYNAMIC 越界参数报错")
    void dynamicBoundaries() {
        LocalCacheSpec.DynamicSpec d = LocalCacheSpec.DynamicSpec.defaults();
        // hotThresholdQps = 0 非法
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 1_000,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                0, d.coolQps(), d.window(), d.windowSlices(), 100, d.syncInterval(),
                d.cooldown(), d.coordination(), null, d.sketchWidthBits(),
                d.sketchDepth(), d.hotReportPartitions(), d.hotReportBatchMax(),
                d.candidateCapacity())).validate());
        // coolQps 必须严格小于 hotThresholdQps
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 1_000,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                100, 100, d.window(), d.windowSlices(), 100, d.syncInterval(),
                d.cooldown(), d.coordination(), null, d.sketchWidthBits(),
                d.sketchDepth(), d.hotReportPartitions(), d.hotReportBatchMax(),
                d.candidateCapacity())).validate());
        // sketchWidthBits 越界（< 10）
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 1_000,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                100, 25, d.window(), d.windowSlices(), 100, d.syncInterval(),
                d.cooldown(), d.coordination(), null, 9,
                d.sketchDepth(), d.hotReportPartitions(), d.hotReportBatchMax(),
                d.candidateCapacity())).validate());
        // windowSlices < 2
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 1_000,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                100, 25, d.window(), 1, 100, d.syncInterval(),
                d.cooldown(), d.coordination(), null, d.sketchWidthBits(),
                d.sketchDepth(), d.hotReportPartitions(), d.hotReportBatchMax(),
                d.candidateCapacity())).validate());
    }

    @Test
    @DisplayName("GLOBAL 缺少 global 配置时报错")
    void globalRequiresSpec() {
        LocalCacheSpec spec = LocalCacheSpec.dynamicCache("p", 1_000, Duration.ofSeconds(60),
                LocalCacheSpec.DynamicSpec.defaults().withGlobal(null));
        assertThrows(CacheException.class, spec::validate);
    }

    @Test
    @DisplayName("cooldown 必须不短于 demoteWindows × global.window")
    void cooldownMustCoverDemoteWindows() {
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100_000L, 1);
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        // cooldown 30s >= 3 × 10s：通过
        LocalCacheSpec.dynamicCache("p", 1_000, Duration.ofSeconds(60),
                defaults.withGlobal(global)).validate();

        // window 放大到 20s → 需 60s，30s 不够
        LocalCacheSpec.GlobalSpec slowWindow = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(20), 100_000L, 1);
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 1_000,
                Duration.ofSeconds(60), defaults.withGlobal(slowWindow)).validate());
    }

    @Test
    @DisplayName("预选阈值（QPS）：单机取 hotThresholdQps-1；GLOBAL 取全局份额")
    void candidateQps() {
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        // 单机：clamp 到 L-1（100-1=99）
        assertEquals(99L, defaults.candidateQps());

        // GLOBAL：globalHotQps=1000、50 客户端 → 20 QPS 份额，被 L-1=99 夹住后取 20
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 1_000L, 50);
        LocalCacheSpec.DynamicSpec withGlobal = defaults.withGlobal(global);
        assertEquals(20L, withGlobal.candidateQps());
        assertTrue(withGlobal.candidateQps() < withGlobal.hotThresholdQps());

        // 份额大于本地阈值时被 L-1 夹住
        LocalCacheSpec.DynamicSpec small = new LocalCacheSpec.DynamicSpec(
                10, 2, Duration.ofSeconds(5), 16, 100, Duration.ofSeconds(1),
                Duration.ofSeconds(30), LocalCacheSpec.Coordination.GLOBAL,
                LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100_000L, 50),
                12, 4, 16, 16, 1_000);
        assertEquals(9L, small.candidateQps(), "份额 2000 应被 L-1=9 夹住");
    }

    @Test
    @DisplayName("QPS 阈值换算成窗口累计次数：hot = qps × 窗口秒数")
    void qpsConvertsToWindowCount() {
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        // 默认 100 QPS × 5s = 500 次
        assertEquals(500L, defaults.hotThresholdCount());
        assertEquals(defaults.candidateQps() * 5L, defaults.candidateCount());

        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100L, 4);
        assertEquals(1_000L, global.hotCount(), "全局 100 QPS × 10s");
        assertEquals(25L, global.coolQps(), "冷阈值取热阈值 1/4");
        assertEquals(250L, global.coolCount());
    }

    @Test
    @DisplayName("sketchWidth() 由位数换算成格数")
    void sketchWidthFromBits() {
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        assertEquals(12, defaults.sketchWidthBits());
        assertEquals(4_096, defaults.sketchWidth());
        assertEquals(Duration.ofNanos(312_500_000L), defaults.windowSliceLength(),
                "5s / 16 片 = 312.5ms");
    }
}
