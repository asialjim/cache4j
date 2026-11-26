package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.twolevel.Cache4jImpl;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.Cache4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 策略的<strong>读路径</strong>：仓库 + 自举缓存。
 *
 * <p>只回答"现在的生效策略是什么"——不做分页、不做变更。运维能力在
 * {@link CacheStrategyAdministrator}，两者共享同一个 {@link DefaultCacheStrategyRepository}。</p>
 *
 * <p>自举缓存让读策略不必每次打数据源；跨节点传播靠"对账发现 configVersion 变化 → 重新解析"
 * （{@link #resolveIfChanged}）。</p>
 *
 * @author Asial Jim
 */
public final class CacheStrategyCache implements CacheStrategyRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CacheStrategyCache.class);

    private final CacheStrategyDataSource dataSource;

    private final DefaultCacheStrategyRepository delegate;

    private final Cache4j<String, StoredPolicy> policyCache;

    /**
     * @param dataSource  策略数据源
     * @param selfOptions 自举缓存配置
     * @param localCache  自举缓存的本地层
     * @param centralCache 自举缓存的中央层
     */
    public CacheStrategyCache(CacheStrategyDataSource dataSource,
                              StrategySelfCacheOptions selfOptions,
                              com.asialjim.cache4j.core.local.LocalCache<StoredPolicy> localCache,
                              com.asialjim.cache4j.core.central.CentralCache<StoredPolicy> centralCache) {
        this(dataSource, selfOptions, localCache, centralCache, System::nanoTime, System::currentTimeMillis);
    }

    /**
     * 完整构造器（测试可注入时钟；三层必须共用同一套时钟）。
     *
     * @param dataSource   策略数据源
     * @param selfOptions  自举缓存配置
     * @param localCache   本地层
     * @param centralCache 中央层
     * @param nanoClock    纳秒时钟
     * @param milliClock   毫秒时钟
     */
    public CacheStrategyCache(CacheStrategyDataSource dataSource,
                              StrategySelfCacheOptions selfOptions,
                              com.asialjim.cache4j.core.local.LocalCache<StoredPolicy> localCache,
                              com.asialjim.cache4j.core.central.CentralCache<StoredPolicy> centralCache,
                              java.util.function.LongSupplier nanoClock,
                              java.util.function.LongSupplier milliClock) {
        this.dataSource = dataSource;
        this.delegate = new DefaultCacheStrategyRepository(dataSource);
        this.policyCache = Cache4jImpl.<String, StoredPolicy>builder(StrategySelfCacheOptions.SELF_NAMESPACE)
                .local(localCache)
                .central(centralCache)
                .build();
    }

    @Override
    public void declare(CacheStrategyDecl decl, String origin) {
        delegate.declare(decl, origin);
    }

    public List<String> bootstrap() {
        List<String> namespaces = delegate.bootstrap();
        // 把解析结果回填自举缓存：之后读策略命中本地/中央，不再每次打数据源
        int filled = 0;
        for (String namespace : namespaces) {
            NamespacePolicy policy = delegate.find(namespace).policyOrNull();
            if (policy == null) {
                continue;
            }
            try {
                policyCache.put(namespace, toStored(policy));
                filled++;
            } catch (RuntimeException e) {
                LOG.warn("[{}]-{}-{}", "策略仓库", "启动", "回填自举缓存失败（不影响本次装配，下次读会重新回源）：namespace="
                                + namespace + "，原因=" + e.getMessage());
            }
        }
        LOG.debug("[{}]-{}-{}", "策略仓库", "启动", "解析完成，共 " + namespaces.size() + " 个 namespace，回填自举缓存 " + filled + " 条");
        return namespaces;
    }

    @Override

    public PolicyLookup find(String namespace) {
        Lookup<StoredPolicy> cached;
        try {
            cached = policyCache.lookup(namespace);
        } catch (RuntimeException e) {
            return new PolicyLookup.Unavailable(e);
        }
        if (cached.isNegative()) {
            return new PolicyLookup.Absent();
        }
        if (cached.isHit()) {
            StoredPolicy stored = cached.optional().orElse(null);
            return stored == null ? new PolicyLookup.Absent() : resolveIfChanged(namespace, stored);
        }
        // 自举缓存未命中 → 回源数据源
        Optional<StoredPolicy> loaded;
        try {
            loaded = dataSource.load(namespace);
        } catch (RuntimeException e) {
            return new PolicyLookup.Unavailable(e);
        }
        if (loaded.isEmpty()) {
            // 负缓存：把"没有策略"这个结论也缓存住（中央空值 TTL），避免每次启动都穿透到数据源
            try {
                policyCache.put(namespace, null);
            } catch (RuntimeException e) {
                LOG.debug("[{}]-{}-{}", "策略仓库", "回源", "负缓存写入失败（不影响结论）：namespace=" + namespace + "，原因=" + e.getMessage());
            }
            return new PolicyLookup.Absent();
        }
        StoredPolicy stored = loaded.get();
        try {
            // 条件插入：避免把"读到的旧值"覆盖掉别人刚写穿的新值
            policyCache.putIfAbsent(namespace, stored, null);
        } catch (RuntimeException e) {
            LOG.debug("[{}]-{}-{}", "策略仓库", "回源", "回填自举缓存失败（不影响本次结论）：namespace=" + namespace + "，原因=" + e.getMessage());
        }
        return resolveIfChanged(namespace, stored);
    }

    @Override
    public List<NamespacePolicy> all() {
        return delegate.all();
    }

    private PolicyLookup resolveIfChanged(String namespace, StoredPolicy stored) {
        NamespacePolicy current = delegate.find(namespace).policyOrNull();
        if (current != null
                && current.source() == NamespacePolicy.Source.REPOSITORY
                && current.configVersion() == stored.configVersion()) {
            return new PolicyLookup.Found(current);
        }
        Optional<CacheStrategyDecl> decl = delegate.declarationOf(namespace);
        try {
            NamespacePolicy resolved = PolicyResolver.fromStored(decl.orElse(null), stored);
            delegate.applyResolved(resolved);
            if (current != null) {
                LOG.debug("[{}]-{}-{}", "策略仓库", "刷新", "namespace=" + namespace
                        + "，configVersion " + current.configVersion() + "→" + resolved.configVersion()
                        + "，strategy=" + current.strategy() + "→" + resolved.strategy());
            }
            return new PolicyLookup.Found(resolved);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-{}-{}", "策略仓库", "刷新", "策略记录解析失败，视为不可用：namespace=" + namespace + "，原因=" + e.getMessage());
            return new PolicyLookup.Unavailable(e);
        }
    }

    // ------------------------------------------------------------------ 给管理面用的口子

    /**
     * @return 底层仓库（管理面需要与读路径共享同一个快照）
     */
    DefaultCacheStrategyRepository repository() {
        return delegate;
    }

    /**
     * 无条件覆盖自举缓存（管理面写穿用：刚从数据源拿到的权威值）。
     *
     * @param namespace 命名空间
     * @param stored    权威记录
     */
    void putPolicy(String namespace, StoredPolicy stored) {
        policyCache.put(namespace, stored);
    }

    /**
     * 清掉自举缓存里的一条（写穿失败时的兜底：下次读回源）。
     *
     * @param namespace 命名空间
     */
    void evictPolicy(String namespace) {
        policyCache.evict(namespace);
    }

    /**
     * 关掉自举缓存（只关本地；中央连接由 provider 负责）。
     */
    public void close() {
        policyCache.close();
    }

    /**
     * @return 自举缓存视图（测试与排障用；不要拿它做业务读写）
     */
    public Cache4j<String, StoredPolicy> selfCache() {
        return policyCache;
    }

    /**
     * @return 自举缓存的 key（用于测试与排障）
     */
    static CacheKey selfKey(String namespace) {
        return new CacheKey(StrategySelfCacheOptions.SELF_NAMESPACE, namespace);
    }

    /**
     * 转成可持久化记录（管理面写穿时需要）。
     *
     * @param policy 生效值
     * @return 存储记录
     */
    static StoredPolicy toStored(NamespacePolicy policy) {
        return new StoredPolicy(CacheStrategyProperty.of(policy),
                policy.configVersion(), policy.updatedAtMillis(), policy.updatedBy(), policy.reason());
    }
}
