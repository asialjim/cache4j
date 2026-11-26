package com.asialjim.cache4j.central;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.GlobalSpec;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 内存中央缓存 provider：<strong>仅供测试与单机开发</strong>。
 *
 * <p>生产环境必须显式选择 Redis；框架适配器在既没有 Redis 也没有显式 {@code memory} 配置时
 * 应在启动阶段报配置错误，而不是悄悄用内存缓存。</p>
 *
 * @author Asial Jim
 */
public final class InMemoryCentralCacheProvider implements CentralCacheProvider {

    private final LongSupplier milliClock;
    private final InMemoryHotKeyCoordinator coordinator;

    /**
     * 不提供热 key 协调：{@code DYNAMIC + GLOBAL} 会启动失败。
     */
    public InMemoryCentralCacheProvider() {
        this(System::currentTimeMillis, null);
    }

    /**
     * @param milliClock     毫秒时钟
     * @param globalSpecs    namespace → 中央阈值解析器；为 {@code null} 时不提供协调
     */
    public InMemoryCentralCacheProvider(LongSupplier milliClock,
                                        Function<String, GlobalSpec> globalSpecs) {
        this.milliClock = milliClock;
        this.coordinator = globalSpecs == null ? null : new InMemoryHotKeyCoordinator(globalSpecs, milliClock);
    }

    @Override
    public <V> CentralCache<V> create(CacheStrategy strategy) {
        return new InMemoryCentralCache<>(strategy, milliClock);
    }

    @Override
    public Optional<HotKeyCoordinator> hotKeyCoordinator() {
        return Optional.ofNullable(coordinator);
    }

    @Override
    public void close() {
        if (coordinator != null) {
            coordinator.close();
        }
    }
}
