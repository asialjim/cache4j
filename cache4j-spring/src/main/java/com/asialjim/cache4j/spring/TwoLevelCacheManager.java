package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 二级缓存管理器：按定义装配并持有若干命名缓存，负责生命周期。
 *
 * <p>装配顺序对每个命名缓存是：本地缓存（来自 {@code LocalCacheProvider}，带热 key 协调器）
 * → 中央缓存（来自 {@code CentralCacheProvider}）→ 门面 {@link TwoLevelCache}。</p>
 *
 * <p>{@link #close()} 幂等：先关缓存，再按需关闭中央 provider（连接可能被多个缓存共享，
 * 所以只在 {@link Cache4jSpringOptions#ownsCentralProvider()} 为真时关闭）。</p>
 *
 * @author Asial Jim
 */
public final class TwoLevelCacheManager implements AutoCloseable {

    private final Map<String, TwoLevelCache<Object, Object>> caches;
    private final CentralCacheProvider centralProvider;
    private final boolean ownsCentralProvider;

    private TwoLevelCacheManager(Map<String, TwoLevelCache<Object, Object>> caches,
                                 CentralCacheProvider centralProvider,
                                 boolean ownsCentralProvider) {
        this.caches = caches;
        this.centralProvider = centralProvider;
        this.ownsCentralProvider = ownsCentralProvider;
    }

    /**
     * 按配置装配缓存。
     *
     * @param options 配置
     * @return 管理器
     */
    public static TwoLevelCacheManager create(Cache4jSpringOptions options) {
        Map<String, TwoLevelCache<Object, Object>> built = new LinkedHashMap<>();
        if (!options.enabled()) {
            return new TwoLevelCacheManager(built, options.centralProvider(), false);
        }
        List<CacheDefinition> definitions = options.definitions();
        if (definitions.isEmpty()) {
            throw new IllegalStateException("至少需要一条缓存定义");
        }
        // 同一个中央 provider 的热 key 协调器被所有 DYNAMIC 缓存共享
        HotKeyCoordinator coordinator = options.centralProvider().hotKeyCoordinator().orElse(null);

        for (CacheDefinition definition : definitions) {
            String namespace = definition.namespace();
            if (built.containsKey(namespace)) {
                throw new IllegalStateException("重复的缓存名：" + namespace);
            }
            LocalCacheSpec localSpec = definition.local();
            localSpec.validate();

            LocalCache<Object> local = options.localProvider().create(localSpec, coordinator);
            CentralCache<Object> central = options.centralProvider().create(definition.central());

            TwoLevelCache<Object, Object> cache = TwoLevelCache.<Object, Object>builder(namespace)
                    .keyEncoder(options.keyEncoder())
                    .local(local)
                    .central(central)
                    .hotKeyCoordinator(coordinator)
                    .statsEnabled(options.statsEnabled())
                    .build();
            built.put(namespace, cache);
        }
        return new TwoLevelCacheManager(built, options.centralProvider(),
                options.ownsCentralProvider());
    }

    /**
     * @param name 缓存名
     * @return 缓存；不存在返回空
     */
    public Optional<TwoLevelCache<Object, Object>> cache(String name) {
        return Optional.ofNullable(caches.get(name));
    }

    /**
     * @return 全部缓存名（保持定义顺序）
     */
    public Set<String> names() {
        return caches.keySet();
    }

    /**
     * @return 全部缓存视图
     */
    public Collection<TwoLevelCache<Object, Object>> caches() {
        return caches.values();
    }

    @Override
    public void close() {
        caches.values().forEach(cache -> {
            try {
                cache.close();
            } catch (RuntimeException ignored) {
                // 关闭阶段的异常不应阻断其它缓存释放
            }
        });
        caches.clear();
        if (ownsCentralProvider && centralProvider != null) {
            centralProvider.close();
        }
    }
}
