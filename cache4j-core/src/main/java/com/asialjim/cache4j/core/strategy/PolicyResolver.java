package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.DynamicSpec;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
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
        com.asialjim.cache4j.core.CacheStrategy strategy = definition.strategy();
        return new NamespacePolicy(
                decl.getNamespace(), decl.getDescription(), decl.getStrategy(), decl.getStrategy(),
                strategy.localMaxSize(), strategy.localTtl(), definition.localNullTtlDivisor(),
                com.asialjim.cache4j.core.VersionSpec.of(strategy),
                strategy.isDynamic() ? com.asialjim.cache4j.core.DynamicSpec.of(strategy) : null,
                strategy.centralTtl(), strategy.centralNullTtl(), strategy.failOpen(),
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
        long maxSize = stored.maxSize();
        Duration localTtl = stored.localTtl();
        int divisor = stored.localNullTtlDivisor();
        VersionSpec version = stored.version();
        DynamicSpec dynamic = stored.dynamic() != null
                ? stored.dynamic()
                : (strategy == LocalStrategy.DYNAMIC ? DynamicSpec.defaults() : null);
        Duration centralTtl = stored.centralTtl();
        Duration centralNullTtl = stored.centralNullTtl();
        boolean failOpen = stored.failOpen();
        return new NamespacePolicy(
                stored.namespace(), description, strategy,
                decl == null ? null : decl.getStrategy(),
                maxSize, localTtl, divisor, version, dynamic,
                centralTtl, centralNullTtl, failOpen,
                stored.configVersion(), stored.updatedAtMillis(), stored.updatedBy(), stored.reason(),
                NamespacePolicy.Source.REPOSITORY);
    }
}
