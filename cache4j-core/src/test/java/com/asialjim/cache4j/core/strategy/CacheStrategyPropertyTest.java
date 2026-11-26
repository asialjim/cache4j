package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.core.DynamicSpec;

import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CacheStrategyProperty} 的往返转换。
 *
 * <p>重点验证三件事：{@code null} 用内建默认补齐、时间单位按层还原、扁平字段能完整表达
 * 原来的 {@code LocalCacheSpec} / {@code CentralCacheSpec}。</p>
 *
 * @author Asial Jim
 */
class CacheStrategyPropertyTest {

    @Test
    @DisplayName("null 字段用内建默认补齐")
    void nullsFallBackToDefaults() {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace("user:detail");
        property.setLocalStrategy(LocalStrategy.STATIC.name());

        LocalCacheSpec spec = property.toLocalSpec();
        assertEquals(LocalStrategy.STATIC, spec.strategy());
        assertEquals(BuiltinDefaults.MAX_SIZE, spec.maxSize());
        assertEquals(BuiltinDefaults.LOCAL_TTL, spec.ttl(), "未设置 TTL 时用内建默认");
        assertNull(spec.dynamic());
    }

    @Test
    @DisplayName("时间单位按层还原：本地秒、中央分钟")
    void timeUnitsAreRestored() {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace("user:detail");
        property.setLocalStrategy(LocalStrategy.STATIC.name());
        property.setLocalTimeUnit("SECONDS");
        property.setCentralTimeUnit("MINUTES");
        property.setLocalMaxSize(500L);
        property.setLocalTtl(45L);
        property.setCentralTtl(2L);
        property.setCentralNullTtl(30L);
        property.setFailOpen(false);

        assertEquals(Duration.ofSeconds(45), property.toLocalSpec().ttl());

        property.setCentralTimeUnit("SECONDS");
        CentralCacheSpec central = property.toCentralSpec();
        assertEquals(Duration.ofSeconds(2), central.ttl());
        assertEquals(Duration.ofSeconds(30), central.nullTtl());
        assertEquals(false, central.failOpen());
    }

    @Test
    @DisplayName("NONE 策略：不构造版本与动态参数")
    void noneStrategy() {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace("user:detail");
        property.setLocalStrategy(LocalStrategy.NONE.name());

        LocalCacheSpec spec = property.toLocalSpec();
        assertEquals(LocalStrategy.NONE, spec.strategy());
        assertEquals(0L, spec.maxSize());
        assertNull(spec.version());
        assertNull(spec.dynamic());
    }

    @Test
    @DisplayName("DYNAMIC + GLOBAL：热 key 参数与中央阈值都能完整表达")
    void dynamicWithGlobalCoordination() {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace("user:heat");
        property.setLocalStrategy(LocalStrategy.DYNAMIC.name());
        property.setLocalTimeUnit("MILLISECONDS");
        property.setLocalMaxSize(2_000L);
        property.setLocalTtl(60_000L);
        property.setVersionCheckInterval(500L);
        property.setHotThresholdQps(120L);
        property.setCoolQps(30L);
        property.setHotWindow(2_000L);
        property.setHotWindowSlices(16);
        property.setMaxHotKeys(800);
        property.setHotSyncInterval(300L);
        property.setHotCooldown(9_000L);
        property.setCoordination("GLOBAL");
        property.setGlobalWindow(1_500L);
        property.setGlobalHotQps(200L);
        property.setGlobalCoolQps(50L);
        property.setGlobalDemoteWindows(2);
        property.setGlobalStaleWindows(5);
        property.setGlobalClientEstimate(3);

        LocalCacheSpec spec = property.toLocalSpec();
        assertEquals(LocalStrategy.DYNAMIC, spec.strategy());
        assertEquals(Duration.ofMinutes(1), spec.ttl());
        assertEquals(Duration.ofMillis(500), spec.version().checkInterval());

        DynamicSpec dynamic = spec.dynamic();
        assertEquals(120, dynamic.hotThresholdQps());
        assertEquals(30, dynamic.coolQps());
        assertEquals(Duration.ofSeconds(2), dynamic.window());
        assertEquals(16, dynamic.windowSlices());
        assertEquals(800, dynamic.maxHotKeys());
        assertEquals(Duration.ofMillis(300), dynamic.syncInterval());
        assertEquals(Duration.ofSeconds(9), dynamic.cooldown());
        assertEquals(com.asialjim.cache4j.core.Coordination.GLOBAL, dynamic.coordination());

        GlobalSpec global = dynamic.global();
        assertEquals(Duration.ofMillis(1_500), global.window());
        assertEquals(200L, global.hotQps());
        assertEquals(50L, global.coolQps());
        assertEquals(2, global.demoteWindows());
        assertEquals(5, global.staleWindows());
        assertEquals(3, global.clientEstimate());
    }

    @Test
    @DisplayName("QPS 字段不受时间单位影响")
    void qpsIsIndependentOfTimeUnit() {
        CacheStrategyProperty property = new CacheStrategyProperty();
        property.setNamespace("user:heat");
        property.setLocalStrategy(LocalStrategy.DYNAMIC.name());
        property.setHotThresholdQps(50L);
        property.setHotWindow(2L);

        property.setLocalTimeUnit("SECONDS");
        assertEquals(50, property.toLocalSpec().dynamic().hotThresholdQps(),
                "窗口 2 秒时阈值仍是 50 QPS");

        property.setLocalTimeUnit("MINUTES");
        assertEquals(50, property.toLocalSpec().dynamic().hotThresholdQps(),
                "把窗口单位换成分钟，QPS 数值不该被改写成 50×60");
    }
}
