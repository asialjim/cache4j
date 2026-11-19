package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.log.CacheLogger;
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

    private static final CacheLogger LOG = CacheLogger.get(TwoLevelCacheManager.class);

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
            LOG.info(CacheLogger.GLOBAL_SUBJECT, "装配", "cache4j 已禁用（cache4j.enabled=false），不创建任何缓存");
            return new TwoLevelCacheManager(built, options.centralProvider(), false);
        }
        List<CacheDefinition> definitions = options.definitions();
        if (definitions.isEmpty()) {
            LOG.error(CacheLogger.GLOBAL_SUBJECT, "装配", "至少需要一条缓存定义");
            throw new IllegalStateException("至少需要一条缓存定义");
        }
        // 同一个中央 provider 的热 key 协调器被所有 DYNAMIC 缓存共享
        HotKeyCoordinator coordinator = options.centralProvider().hotKeyCoordinator().orElse(null);

        for (CacheDefinition definition : definitions) {
            String namespace = definition.namespace();
            if (built.containsKey(namespace)) {
                LOG.error(CacheLogger.GLOBAL_SUBJECT, "装配", "重复的缓存名：" + namespace);
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
            LOG.info(namespace, "装配", "缓存装配完成，strategy=" + localSpec.strategy()
                    + "，localTtl=" + localSpec.ttl()
                    + "，centralTtl=" + definition.central().ttl()
                    + "，coordinator=" + (coordinator == null ? "none" : coordinator.getClass().getSimpleName()));
        }
        LOG.info(CacheLogger.GLOBAL_SUBJECT, "装配", "共装配 " + built.size() + " 个缓存："
                + String.join(", ", built.keySet()));
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
        LOG.info(CacheLogger.GLOBAL_SUBJECT, "关闭", "关闭 " + caches.size() + " 个缓存"
                + (ownsCentralProvider ? "，并关闭中央 provider" : ""));
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
