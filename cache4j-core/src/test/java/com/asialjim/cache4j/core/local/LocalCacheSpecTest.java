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
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 100,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                0, defaults.window(), 100, defaults.hotTtl(), defaults.syncInterval(),
                defaults.cooldown(), defaults.coordination(), null, defaults.sketchWidth(),
                defaults.sketchDepth(), defaults.hotReportPartitions(), defaults.hotReportBatchMax(),
                defaults.candidateCapacity())).validate());
        assertThrows(CacheException.class, () -> LocalCacheSpec.dynamicCache("p", 100,
                Duration.ofSeconds(60), new LocalCacheSpec.DynamicSpec(
                5, defaults.window(), 100, defaults.hotTtl(), defaults.syncInterval(),
                defaults.cooldown(), defaults.coordination(), null, 1_000,
                defaults.sketchDepth(), defaults.hotReportPartitions(), defaults.hotReportBatchMax(),
                defaults.candidateCapacity())).validate());
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
    @DisplayName("预选阈值严格小于本地升热阈值，且不超过按客户端数摊薄后的份额")
    void candidateThreshold() {
        LocalCacheSpec.DynamicSpec defaults = LocalCacheSpec.DynamicSpec.defaults();
        // 单机：clamp 到 L-1
        assertEquals(4L, defaults.candidateThreshold());

        // GLOBAL：G=100k、50 客户端 → 2k 份额，被 L-1=4 夹住
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100_000L, 50);
        LocalCacheSpec.DynamicSpec withGlobal = defaults.withGlobal(global);
        assertTrue(withGlobal.candidateThreshold() < withGlobal.localHotThreshold());

        // 把 L 调大后，份额才能真正决定阈值
        LocalCacheSpec.DynamicSpec large = new LocalCacheSpec.DynamicSpec(
                10_000, Duration.ofSeconds(10), 10_000, Duration.ofSeconds(60), Duration.ofSeconds(1),
                Duration.ofSeconds(30), LocalCacheSpec.Coordination.GLOBAL,
                LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100_000L, 50),
                4_096, 4, 16, 64, 10_000);
        assertEquals(2_000L, large.candidateThreshold());
    }

    @Test
    @DisplayName("窗口口径不一致时按比例换算预选阈值")
    void candidateThresholdScalesWithWindow() {
        // 本地窗口 5s、中央窗口 10s：份额要减半
        LocalCacheSpec.DynamicSpec spec = new LocalCacheSpec.DynamicSpec(
                10_000, Duration.ofSeconds(5), 10_000, Duration.ofSeconds(60), Duration.ofSeconds(1),
                Duration.ofSeconds(30), LocalCacheSpec.Coordination.GLOBAL,
                LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(10), 100_000L, 50),
                4_096, 4, 16, 64, 10_000);
        assertEquals(1_000L, spec.candidateThreshold());
    }
}
