package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;
import java.util.Objects;

/**
 * 一个 namespace 的<strong>生效配置</strong>（解析三层优先级之后的完整值）。
 *
 * <p>解析顺序：数据源里的 {@link StoredPolicy} → 声明值 → 内建默认（{@link BuiltinDefaults}）。
 * 整条生效，不做字段级合并的语义混乱：数据源有记录就以它为准，缺省的字段用内建默认补齐。</p>
 *
 * @param namespace        命名空间
 * @param description      说明（运维可改写，缺省用声明值）
 * @param strategy         生效的本地策略
 * @param declaredStrategy 声明值（只用于审计与展示，**不提供 reset**）
 * @param maxSize          本地条目上限
 * @param localTtl         本地非空副本存活
 * @param localNullTtlDivisor 本地空值副本除子 N（{@code localNullTtl = min(localTtl, centralNullTtl / N)}）
 * @param version          版本对账参数
 * @param dynamic          {@code DYNAMIC} 参数（非 DYNAMIC 时保留但不生效）
 * @param centralTtl       中央非空存活
 * @param centralNullTtl   中央空值存活
 * @param failOpen         中央不可用是否降级
 * @param configVersion    配置版本号（运维乐观锁用，不是缓存 key 的 revision）
 * @param updatedAtMillis  最近变更时间
 * @param updatedBy        变更人
 * @param reason           变更原因
 * @param source           来源
 * @author Asial Jim
 */
public record NamespacePolicy(
        String namespace,
        String description,
        LocalStrategy strategy,
        LocalStrategy declaredStrategy,
        long maxSize,
        Duration localTtl,
        int localNullTtlDivisor,
        LocalCacheSpec.VersionSpec version,
        LocalCacheSpec.DynamicSpec dynamic,
        Duration centralTtl,
        Duration centralNullTtl,
        boolean failOpen,
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason,
        Source source
) {

    /**
     * 配置来源。
     */
    public enum Source {

        /**
         * 完全来自声明 + 内建默认。
         */
        DECLARED,

        /**
         * 来自策略数据源。
         */
        REPOSITORY
    }

    /**
     * 紧凑构造器：非空与基本校验。
     */
    public NamespacePolicy {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(source, "source");
        if (strategy != LocalStrategy.NONE) {
            if (maxSize <= 0L) {
                throw invalid("maxSize 必须大于 0：" + maxSize);
            }
            if (localTtl == null || localTtl.isZero() || localTtl.isNegative()) {
                throw invalid("localTtl 必须为正：" + localTtl);
            }
            if (version == null) {
                throw invalid("STATIC / DYNAMIC 的 version 不允许为 null");
            }
        }
        if (strategy == LocalStrategy.DYNAMIC && dynamic == null) {
            throw invalid("DYNAMIC 必须提供 dynamic 参数");
        }
        if (localNullTtlDivisor < 1) {
            throw invalid("localNullTtlDivisor 必须 >= 1：" + localNullTtlDivisor);
        }
        if (centralTtl == null || centralTtl.isZero() || centralTtl.isNegative()) {
            throw invalid("centralTtl 必须为正：" + centralTtl);
        }
        if (centralNullTtl == null || centralNullTtl.isNegative()) {
            throw invalid("centralNullTtl 不能为负：" + centralNullTtl);
        }
    }

    /**
     * 转成装配定义。
     *
     * @return 装配定义
     */
    public CacheDefinition toDefinition() {
        LocalCacheSpec local = switch (strategy) {
            case NONE -> LocalCacheSpec.noLocal(namespace);
            case STATIC -> LocalCacheSpec.staticCache(namespace, maxSize, localTtl, version);
            case DYNAMIC -> LocalCacheSpec.dynamicCache(namespace, maxSize, localTtl, version, dynamic);
        };
        CentralCacheSpec central = CentralCacheSpec.builder(namespace)
                .ttl(centralTtl)
                .nullTtl(centralNullTtl)
                .failOpen(failOpen)
                .build();
        CacheDefinition definition = CacheDefinition.of(local, central, localNullTtlDivisor);
        definition.validate();
        return definition;
    }

    /**
     * @return 生效值是否与声明值一致（仅展示用，<strong>不告警</strong>）
     */
    public boolean consistentWithDeclared() {
        return strategy == declaredStrategy;
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
