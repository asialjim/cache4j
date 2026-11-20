package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 三层解析：数据源记录 → 声明值 → 内建默认。
 *
 * <p>抽成独立工具是因为两条路径都要用它：仓库门面（启动装配、变更提交）与运维视图
 * （分页查询的每一条记录）。复制一份解析规则必然漂移。</p>
 *
 * @author Asial Jim
 */
public final class PolicyResolver {

    private PolicyResolver() {
    }

    /**
     * 只有声明（数据源无记录）。
     *
     * @param decl 声明
     * @return 生效配置
     */
    public static NamespacePolicy fromDeclaration(CacheStrategyDecl decl) {
        CacheDefinition definition = BuiltinDefaults.definitionOf(decl.getNamespace(), decl.getStrategy());
        LocalCacheSpec local = definition.local();
        return new NamespacePolicy(
                decl.getNamespace(), decl.getDescription(), decl.getStrategy(), decl.getStrategy(),
                local.maxSize(), local.ttl(), definition.localNullTtlDivisor(),
                local.version(), local.dynamic(),
                definition.central().ttl(), definition.central().nullTtl(), definition.central().failOpen(),
                0L, 0L, null, null, NamespacePolicy.Source.DECLARED);
    }

    /**
     * 数据源记录 + 声明（缺省字段用内建默认补齐）。
     *
     * @param decl   声明；{@code null} 表示数据源有、代码里没声明（孤儿配置）
     * @param stored 数据源记录
     * @return 生效配置
     */
    public static NamespacePolicy fromStored(CacheStrategyDecl decl, StoredPolicy stored) {
        LocalStrategy strategy = stored.strategy() != null
                ? stored.strategy()
                : (decl == null ? null : decl.getStrategy());
        if (strategy == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC,
                    "数据源记录缺少 strategy 且没有声明可兜底：" + stored.namespace());
        }
        String description = stored.description() != null
                ? stored.description()
                : (decl == null ? strategy.name() : decl.getDescription());
        long maxSize = stored.maxSize() == null ? BuiltinDefaults.MAX_SIZE : stored.maxSize();
        Duration localTtl = stored.localTtl() == null ? BuiltinDefaults.LOCAL_TTL : stored.localTtl();
        int divisor = stored.localNullTtlDivisor() == null
                ? BuiltinDefaults.LOCAL_NULL_TTL_DIVISOR : stored.localNullTtlDivisor();
        LocalCacheSpec.VersionSpec version = stored.version() == null
                ? LocalCacheSpec.VersionSpec.defaults() : stored.version();
        LocalCacheSpec.DynamicSpec dynamic = stored.dynamic() != null
                ? stored.dynamic()
                : (strategy == LocalStrategy.DYNAMIC ? LocalCacheSpec.DynamicSpec.defaults() : null);
        Duration centralTtl = stored.centralTtl() == null ? BuiltinDefaults.CENTRAL_TTL : stored.centralTtl();
        Duration centralNullTtl = stored.centralNullTtl() == null
                ? BuiltinDefaults.CENTRAL_NULL_TTL : stored.centralNullTtl();
        boolean failOpen = stored.failOpen() == null || stored.failOpen();
        return new NamespacePolicy(
                stored.namespace(), description, strategy,
                decl == null ? null : decl.getStrategy(),
                maxSize, localTtl, divisor, version, dynamic,
                centralTtl, centralNullTtl, failOpen,
                stored.configVersion(), stored.updatedAtMillis(), stored.updatedBy(), stored.reason(),
                NamespacePolicy.Source.REPOSITORY);
    }
}
