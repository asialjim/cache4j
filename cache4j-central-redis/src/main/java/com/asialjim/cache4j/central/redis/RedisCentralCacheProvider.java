package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.GlobalSpec;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.Optional;
import java.util.function.Function;

/**
 * Redis 语义层的 provider：把一个 {@link RedisValueStore} 装配成命名的 {@link CentralCache} 视图。
 *
 * <p>store 的生命周期由创建它的人负责（它持有连接），provider 只在其上开视图；
 * {@code close()} 会关闭 store，必须幂等。</p>
 *
 * <p><strong>关于泛型</strong>：适配层通常持有 {@code RedisValueStore<Object>}（与 Spring 的
 * {@code RedisTemplate<K, Object>} 同理，serializer 按缓存名解析），本类在 {@link #create} 时做一次
 * 类型收窄——正确性由"同一 namespace 使用同一 serializer"的配置约束保证。</p>
 *
 * @author Asial Jim
 */
public final class RedisCentralCacheProvider implements CentralCacheProvider {

    private final RedisValueStore<Object> store;
    private final HotKeyCoordinator coordinator;

    /**
     * @param store 下行存储
     */
    public RedisCentralCacheProvider(RedisValueStore<Object> store) {
        this(store, (HotKeyCoordinator) null);
    }

    /**
     * @param store       下行存储
     * @param coordinator 热 key 协调器；{@code DYNAMIC + GLOBAL} 时使用
     */
    public RedisCentralCacheProvider(RedisValueStore<Object> store, HotKeyCoordinator coordinator) {
        this.store = store;
        this.coordinator = coordinator;
    }

    /**
     * 用同一 store 构造并同时提供热 key 协调器。
     *
     * @param store       下行存储
     * @param globalSpecs namespace → 中央阈值解析器；为 {@code null} 则不提供协调器
     * @return provider
     */
    public static RedisCentralCacheProvider of(RedisValueStore<Object> store,
                                               Function<String, GlobalSpec> globalSpecs) {
        HotKeyCoordinator coordinator = globalSpecs == null
                ? null : new RedisHotKeyCoordinator(store, globalSpecs);
        return new RedisCentralCacheProvider(store, coordinator);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> CentralCache<V> create(CacheStrategy strategy) {
        RedisValueStore<V> typed = (RedisValueStore<V>) (RedisValueStore<?>) store;
        return new RedisCentralCache<>(strategy, typed);
    }

    @Override
    public Optional<HotKeyCoordinator> hotKeyCoordinator() {
        return Optional.ofNullable(coordinator);
    }

    @Override
    public void close() {
        store.close();
    }
}
