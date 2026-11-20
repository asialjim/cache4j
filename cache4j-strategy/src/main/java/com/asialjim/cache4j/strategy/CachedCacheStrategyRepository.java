package com.asialjim.cache4j.strategy;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.local.LocalCacheProvider;
import com.asialjim.cache4j.core.log.CacheLogger;
import com.asialjim.cache4j.core.strategy.CacheStrategyAdmin;
import com.asialjim.cache4j.core.strategy.CacheStrategyDataSource;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.core.strategy.CacheStrategyRepository;
import com.asialjim.cache4j.core.strategy.ChangeResult;
import com.asialjim.cache4j.core.strategy.DefaultCacheStrategyRepository;
import com.asialjim.cache4j.core.strategy.NamespacePolicy;
import com.asialjim.cache4j.core.strategy.Page;
import com.asialjim.cache4j.core.strategy.PolicyLookup;
import com.asialjim.cache4j.core.strategy.PolicyResolver;
import com.asialjim.cache4j.core.strategy.StoredPolicy;
import com.asialjim.cache4j.core.strategy.StrategyChange;
import com.asialjim.cache4j.core.strategy.StrategyView;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;

import java.util.List;
import java.util.Optional;

/**
 * 仓库门面：<strong>仓库自己就是一个 {@code STATIC} 缓存</strong>（本地 + 中央两层）。
 *
 * <p>这条自我约束把"策略的读取"降级成"缓存读写"，于是版本号、TTL、跨节点一致、降级全部由既有机制承担；
 * 而"权威持久值"交给 {@link CacheStrategyDataSource}（不对接缓存体系）。</p>
 *
 * <p><strong>跨节点传播</strong>：变更写在中央条目里，其他节点在下一次读策略时由自举缓存的
 * <strong>版本对账</strong>发现（{@code versionCheckInterval}，默认 5s），本机靠写穿保证读己之写。
 * 不需要广播、不需要长连接。</p>
 *
 * @author Asial Jim
 */
public final class CachedCacheStrategyRepository implements CacheStrategyRepository, CacheStrategyAdmin {

    private static final CacheLogger LOG = CacheLogger.get(CachedCacheStrategyRepository.class);

    private final CacheStrategyDataSource dataSource;

    private final DefaultCacheStrategyRepository delegate;

    private final TwoLevelCache<String, StoredPolicy> policyCache;

    /**
     * @param dataSource   策略数据源（必填；没有它就不该启用仓库）
     * @param options      自举缓存参数（改需重启）
     * @param localCache   自举缓存的本地层（{@code STATIC}；由装配层按 options.localSpec() 创建）
     * @param centralCache 自举缓存的中央层（{@code StoredPolicy} 的序列化由适配层负责）
     */
    public CachedCacheStrategyRepository(CacheStrategyDataSource dataSource,
                                         StrategySelfCacheOptions options,
                                         LocalCache<StoredPolicy> localCache,
                                         CentralCache<StoredPolicy> centralCache) {
        this(dataSource, options, localCache, centralCache, System::nanoTime, System::currentTimeMillis);
    }

    /**
     * 完整构造器（测试可注入时钟）。
     *
     * <p>三层必须共用同一套时钟：本地层用 {@code nanoClock} 判定副本过期与对账检查点，
     * 门面用同一套 {@code nanoClock} 做对账，中央层用 {@code milliClock} 版本。混用会导致
     * "对账永远不到点"。</p>
     *
     * @param dataSource   数据源
     * @param options      自举缓存参数
     * @param localCache   本地层
     * @param centralCache 中央层
     * @param nanoClock    单调时钟
     * @param milliClock   毫秒时钟
     */
    public CachedCacheStrategyRepository(CacheStrategyDataSource dataSource,
                                         StrategySelfCacheOptions options,
                                         LocalCache<StoredPolicy> localCache,
                                         CentralCache<StoredPolicy> centralCache,
                                         java.util.function.LongSupplier nanoClock,
                                         java.util.function.LongSupplier milliClock) {
        this.dataSource = dataSource;
        this.delegate = new DefaultCacheStrategyRepository(dataSource);
        this.policyCache = TwoLevelCache.<String, StoredPolicy>builder(StrategySelfCacheOptions.SELF_NAMESPACE)
                .local(localCache)
                .central(centralCache)
                .nanoClock(nanoClock)
                .milliClock(milliClock)
                .build();
        LOG.info(CacheLogger.Subject.STRATEGY_REPO, "启动", "自举缓存已装配：" + options.describe());
    }

    /**
     * 便捷装配：用 provider 按 {@code options} 创建自举缓存的两层。
     *
     * @param dataSource      数据源
     * @param options         自举缓存参数
     * @param localProvider   本地缓存 provider
     * @param centralProvider 中央缓存 provider
     * @return 门面
     */
    public static CachedCacheStrategyRepository create(CacheStrategyDataSource dataSource,
                                                       StrategySelfCacheOptions options,
                                                       LocalCacheProvider localProvider,
                                                       CentralCacheProvider centralProvider) {
        return new CachedCacheStrategyRepository(dataSource, options,
                localProvider.create(options.localSpec(), null),
                centralProvider.create(options.centralSpec()));
    }

    // ------------------------------------------------------------------ 仓库（业务端只用这几个）

    @Override
    public void declare(CacheStrategyDecl decl, String origin) {
        delegate.declare(decl, origin);
    }

    @Override
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
                LOG.warnThrottled(CacheLogger.Subject.STRATEGY_REPO, "启动", () ->
                        "回填自举缓存失败（不影响本次装配，下次读会重新回源）：namespace="
                                + namespace + "，原因=" + e.getMessage());
            }
        }
        LOG.info(CacheLogger.Subject.STRATEGY_REPO, "启动",
                "解析完成，共 " + namespaces.size() + " 个 namespace，回填自举缓存 " + filled + " 条");
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
                LOG.debug(CacheLogger.Subject.STRATEGY_REPO, "回源",
                        "负缓存写入失败（不影响结论）：namespace=" + namespace + "，原因=" + e.getMessage());
            }
            return new PolicyLookup.Absent();
        }
        StoredPolicy stored = loaded.get();
        try {
            // 条件插入：避免把"读到的旧值"覆盖掉别人刚写穿的新值
            policyCache.putIfAbsent(namespace, stored, null);
        } catch (RuntimeException e) {
            LOG.debug(CacheLogger.Subject.STRATEGY_REPO, "回源",
                    "回填自举缓存失败（不影响本次结论）：namespace=" + namespace + "，原因=" + e.getMessage());
        }
        return resolveIfChanged(namespace, stored);
    }

    @Override
    public List<NamespacePolicy> all() {
        return delegate.all();
    }

    // ------------------------------------------------------------------ 管理面（只在管理服务装配）

    @Override
    public Page<StrategyView> page(String keyword, int page, int size) {
        return delegate.page(keyword, page, size);
    }

    @Override
    public Optional<StrategyView> view(String namespace) {
        return delegate.view(namespace);
    }

    @Override
    public ChangeResult change(StrategyChange change) {
        ChangeResult result = delegate.change(change);
        if (!result.applied() || result.current() == null) {
            return result;
        }
        return writeThrough(change, result);
    }

    /**
     * 写穿：数据源已成功，把新策略同步进自举缓存。
     *
     * <p>三级降级：写穿 → 失败则 clear（让下次读回源，仍需 ≤5s 完成传播）→ 都失败才是
     * {@code APPLIED_STALE}（本机已生效，其他节点最坏要等中央 TTL）。</p>
     *
     * @param change 请求
     * @param result 委托层结果
     * @return 最终结果
     */
    private ChangeResult writeThrough(StrategyChange change, ChangeResult result) {
        String namespace = change.namespace();
        try {
            // 无条件覆盖：写穿的是刚从数据源拿到的权威值，必然最新
            policyCache.put(namespace, toStored(result.current()));
            return result;
        } catch (RuntimeException writeError) {
            LOG.warn(CacheLogger.Subject.STRATEGY_REPO, "写穿",
                    "写穿失败，退化为 clear 兜底：namespace=" + namespace
                            + "，原因=" + writeError.getMessage());
        }
        try {
            policyCache.evict(namespace);
            return new ChangeResult(ChangeResult.Outcome.APPLIED, namespace,
                    result.configVersion(), result.current(),
                    result.message() + "（写穿失败，已 clear 自举缓存，下次读回源）", result.requestId());
        } catch (RuntimeException clearError) {
            LOG.error(CacheLogger.Subject.STRATEGY_REPO, "写穿",
                    "写穿与 clear 都失败：namespace=" + namespace + "，其他节点可能延迟到中央 TTL 才生效"
                            + "，原因=" + clearError.getMessage(), clearError);
            return new ChangeResult(ChangeResult.Outcome.APPLIED_STALE, namespace,
                    result.configVersion(), result.current(),
                    "已持久化，但自举缓存同步失败（写穿与 clear 均失败），需告警并补偿", result.requestId());
        }
    }

    /**
     * 关掉自举缓存（只关本地；中央连接由 provider 负责）。
     */
    public void close() {
        policyCache.close();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 若配置版本变了就重新解析并更新快照（跨节点传播的落地点）。
     *
     * @param namespace 命名空间
     * @param stored    自举缓存/数据源给出的权威记录
     * @return 三态结果
     */
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
                LOG.info(CacheLogger.Subject.STRATEGY_REPO, "刷新", "namespace=" + namespace
                        + "，configVersion " + current.configVersion() + "→" + resolved.configVersion()
                        + "，strategy=" + current.strategy() + "→" + resolved.strategy());
            }
            return new PolicyLookup.Found(resolved);
        } catch (RuntimeException e) {
            LOG.warnThrottled(CacheLogger.Subject.STRATEGY_REPO, "刷新", () ->
                    "策略记录解析失败，视为不可用：namespace=" + namespace + "，原因=" + e.getMessage());
            return new PolicyLookup.Unavailable(e);
        }
    }

    private static StoredPolicy toStored(NamespacePolicy policy) {
        return new StoredPolicy(
                policy.namespace(), policy.description(), policy.strategy(),
                policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                policy.version(), policy.dynamic(),
                policy.centralTtl(), policy.centralNullTtl(), policy.failOpen(),
                policy.configVersion(), policy.updatedAtMillis(), policy.updatedBy(), policy.reason());
    }

    /**
     * @return 自举缓存视图（测试与排障用；不要拿它做业务读写）
     */
    public TwoLevelCache<String, StoredPolicy> selfCache() {
        return policyCache;
    }

    /**
     * @return 自举缓存的 key（用于测试与排障）
     */
    static CacheKey selfKey(String namespace) {
        return new CacheKey(StrategySelfCacheOptions.SELF_NAMESPACE, namespace);
    }
}
