package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.log.CacheLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认仓库实现：声明登记 + 三层解析（数据源 → 声明 → 内建默认）。
 *
 * <p>数据源可选：为 {@code null} 时退化为"声明 + 内建默认"（不启用仓库的形态）。</p>
 *
 * <p>线程模型：登记与解析发生在启动期（实例锁串行化）；解析完成后 {@link #find}/{@link #all}
 * 读不可变快照，无锁。</p>
 *
 * @author Asial Jim
 */
public final class DefaultCacheStrategyRepository implements CacheStrategyRepository {

    private static final CacheLogger LOG = CacheLogger.get(DefaultCacheStrategyRepository.class);

    private final CacheStrategyDataSource dataSource;

    private final Map<String, DeclEntry> declarations = new LinkedHashMap<>();

    private volatile Map<String, NamespacePolicy> snapshot = Map.of();

    /**
     * @param dataSource 策略数据源；{@code null} 表示不启用仓库（只用声明 + 内建默认）
     */
    public DefaultCacheStrategyRepository(CacheStrategyDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized void declare(CacheStrategyDecl decl, String origin) {
        String namespace = decl.getNamespace();
        DeclEntry previous = declarations.putIfAbsent(namespace, new DeclEntry(decl, origin));
        if (previous == null) {
            return;
        }
        if (previous.origin().equals(origin)) {
            LOG.debug(CacheLogger.Subject.ASSEMBLY, "声明", origin + " 重复登记，已忽略");
            return;
        }
        throw new CacheException(CacheResCode.INVALID_SPEC,
                "namespace 冲突：" + namespace + " 同时声明于 " + previous.origin() + " 与 " + origin);
    }

    @Override
    public synchronized List<String> bootstrap() {
        Map<String, NamespacePolicy> resolved = new LinkedHashMap<>(declarations.size());
        for (Map.Entry<String, DeclEntry> entry : declarations.entrySet()) {
            String namespace = entry.getKey();
            NamespacePolicy policy = resolve(namespace, entry.getValue());
            resolved.put(namespace, policy);
            logResolved(policy);
        }
        this.snapshot = Map.copyOf(resolved);
        LOG.debug(CacheLogger.Subject.ASSEMBLY, "初始化",
                "解析完成，共 " + resolved.size() + " 个 namespace，数据源="
                        + (dataSource == null ? "未启用" : dataSource.getClass().getSimpleName()));
        return new ArrayList<>(resolved.keySet());
    }

    @Override
    public PolicyLookup find(String namespace) {
        NamespacePolicy policy = snapshot.get(namespace);
        return policy == null ? new PolicyLookup.Absent() : new PolicyLookup.Found(policy);
    }

    @Override
    public List<NamespacePolicy> all() {
        return new ArrayList<>(snapshot.values());
    }

    /**
     * 三层解析：数据源 → 声明 → 内建默认。
     *
     * @param namespace 命名空间
     * @param entry     声明
     * @return 生效配置
     */
    private NamespacePolicy resolve(String namespace, DeclEntry entry) {
        CacheStrategyDecl decl = entry.decl();
        if (dataSource == null) {
            return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
        }
        StoredPolicy stored;
        try {
            Optional<StoredPolicy> loaded = dataSource.load(namespace);
            if (loaded.isEmpty()) {
                LOG.debug(CacheLogger.Subject.ASSEMBLY, "初始化",
                        namespace + " 数据源无记录，用声明 + 内建默认");
                return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
            }
            stored = loaded.get();
        } catch (RuntimeException e) {
            // 数据源不可达：不得让业务起不来（除非显式 failClosed，本实现不提供）
            LOG.warnThrottled(CacheLogger.Subject.ASSEMBLY, "初始化", () ->
                    "策略数据源读取失败，namespace=" + namespace + "，按声明 + 内建默认继续，原因=" + e.getMessage());
            return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
        }
        return fromStored(decl, stored);
    }

    private NamespacePolicy fromDeclaration(CacheStrategyDecl decl, NamespacePolicy.Source source) {
        CacheDefinition definition = BuiltinDefaults.definitionOf(decl.getNamespace(), decl.getStrategy());
        LocalCacheSpec local = definition.local();
        return new NamespacePolicy(
                decl.getNamespace(), decl.getDescription(), decl.getStrategy(), decl.getStrategy(),
                local.maxSize(), local.ttl(), local.version(), local.dynamic(),
                definition.central().ttl(), definition.central().nullTtl(), definition.central().failOpen(),
                0L, 0L, null, null, source);
    }

    private NamespacePolicy fromStored(CacheStrategyDecl decl, StoredPolicy stored) {
        LocalStrategy strategy = stored.strategy() == null ? decl.getStrategy() : stored.strategy();
        String description = stored.description() == null ? decl.getDescription() : stored.description();
        long maxSize = stored.maxSize() == null ? BuiltinDefaults.MAX_SIZE : stored.maxSize();
        Duration localTtl = stored.localTtl() == null ? BuiltinDefaults.LOCAL_TTL : stored.localTtl();
        LocalCacheSpec.VersionSpec version = stored.version() == null
                ? LocalCacheSpec.VersionSpec.defaults() : stored.version();
        LocalCacheSpec.DynamicSpec dynamic = stored.dynamic() == null
                ? (strategy == LocalStrategy.DYNAMIC ? LocalCacheSpec.DynamicSpec.defaults() : null)
                : stored.dynamic();
        Duration centralTtl = stored.centralTtl() == null ? BuiltinDefaults.CENTRAL_TTL : stored.centralTtl();
        Duration centralNullTtl = stored.centralNullTtl() == null
                ? BuiltinDefaults.CENTRAL_NULL_TTL : stored.centralNullTtl();
        boolean failOpen = stored.failOpen() == null || stored.failOpen();
        return new NamespacePolicy(
                decl.getNamespace(), description, strategy, decl.getStrategy(),
                maxSize, localTtl, version, dynamic,
                centralTtl, centralNullTtl, failOpen,
                stored.configVersion(), stored.updatedAtMillis(), stored.updatedBy(), stored.reason(),
                NamespacePolicy.Source.REPOSITORY);
    }

    private void logResolved(NamespacePolicy policy) {
        String detail = "namespace=" + policy.namespace()
                + "，strategy=" + policy.strategy()
                + "（声明=" + policy.declaredStrategy() + "）"
                + "，maxSize=" + policy.maxSize()
                + "，localTtl=" + policy.localTtl()
                + "，centralTtl=" + policy.centralTtl()
                + "，centralNullTtl=" + policy.centralNullTtl()
                + "，configVersion=" + policy.configVersion()
                + "，来源=" + policy.source();
        if (policy.consistentWithDeclared()) {
            LOG.info(CacheLogger.Subject.ASSEMBLY, "初始化", detail);
        } else {
            // 声明与生效不一致不告警（业务允许），但信息级留痕便于排障
            LOG.info(CacheLogger.Subject.ASSEMBLY, "初始化",
                    detail + "，注意生效策略与代码声明不同（接受，无需处理）");
        }
    }

    /**
     * 一条已登记的声明。
     *
     * @param decl   声明
     * @param origin 来源
     */
    private record DeclEntry(CacheStrategyDecl decl, String origin) {
    }
}
