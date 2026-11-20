package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 运维视图：把"声明值 / 生效值 / 谁改的"摊平给人看。
 *
 * @param namespace              命名空间
 * @param description            生效说明（运维可改写）
 * @param declaredDescription    声明里的说明（可能已与生效值不同）
 * @param activeStrategy         生效策略
 * @param declaredStrategy       声明策略（仅展示，**不提供 reset**）
 * @param maxSize                本地条目上限
 * @param localTtl               本地非空副本存活
 * @param localNullTtl           本地空值副本存活（派生值：{@code min(localTtl, centralNullTtl / N)}）
 * @param localNullTtlDivisor    上面的除子 N
 * @param version                版本对账参数
 * @param dynamic                {@code DYNAMIC} 参数（非 DYNAMIC 时保留配置但不生效）
 * @param dynamicActive          {@code dynamic} 是否生效
 * @param centralTtl             中央非空存活
 * @param centralNullTtl         中央空值存活
 * @param failOpen               中央不可用是否降级
 * @param source                 配置来源
 * @param configVersion          配置版本号（运维乐观锁用）
 * @param updatedAtMillis        最近变更时间
 * @param updatedBy              变更人
 * @param reason                 变更原因
 * @param consistentWithDeclared 生效策略是否与声明一致（**仅展示，不告警**）
 * @param undeclared             业务用了但没人声明的 namespace
 * @param orphan                 数据源有但始终没被业务用到
 * @param broken                 该记录解析失败（其余字段取内建默认）
 * @param casSupported           数据源是否支持乐观锁 CAS
 * @author Asial Jim
 */
public record StrategyView(
        String namespace,
        String description,
        String declaredDescription,
        LocalStrategy activeStrategy,
        LocalStrategy declaredStrategy,
        long maxSize,
        Duration localTtl,
        Duration localNullTtl,
        int localNullTtlDivisor,
        LocalCacheSpec.VersionSpec version,
        LocalCacheSpec.DynamicSpec dynamic,
        boolean dynamicActive,
        Duration centralTtl,
        Duration centralNullTtl,
        boolean failOpen,
        NamespacePolicy.Source source,
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason,
        boolean consistentWithDeclared,
        boolean undeclared,
        boolean orphan,
        boolean broken,
        boolean casSupported
) {

    /**
     * 由生效配置生成视图。
     *
     * @param policy              生效配置
     * @param declaredDescription 声明里的说明
     * @param casSupported        数据源是否支持 CAS
     * @return 视图
     */
    public static StrategyView of(NamespacePolicy policy, String declaredDescription, boolean casSupported) {
        return new StrategyView(
                policy.namespace(), policy.description(), declaredDescription,
                policy.strategy(), policy.declaredStrategy(),
                policy.maxSize(), policy.localTtl(),
                deriveLocalNullTtl(policy), policy.localNullTtlDivisor(),
                policy.version(), policy.dynamic(),
                policy.strategy() == LocalStrategy.DYNAMIC,
                policy.centralTtl(), policy.centralNullTtl(), policy.failOpen(),
                policy.source(), policy.configVersion(), policy.updatedAtMillis(),
                policy.updatedBy(), policy.reason(),
                policy.consistentWithDeclared(),
                false, false, false, casSupported);
    }

    /**
     * 派生本地空值副本存活时长。
     *
     * @param policy 生效配置
     * @return {@code min(localTtl, centralNullTtl / N)}
     */
    public static Duration deriveLocalNullTtl(NamespacePolicy policy) {
        Duration centralNull = policy.centralNullTtl();
        if (centralNull == null || centralNull.isZero()) {
            return Duration.ZERO;
        }
        Duration derived = centralNull.dividedBy(Math.max(1, policy.localNullTtlDivisor()));
        Duration localTtl = policy.localTtl();
        if (localTtl == null) {
            return derived;
        }
        return derived.compareTo(localTtl) < 0 ? derived : localTtl;
    }

    /**
     * @param undeclared 是否未声明
     * @return 新视图
     */
    public StrategyView withUndeclared(boolean undeclared) {
        return new StrategyView(namespace, description, declaredDescription, activeStrategy, declaredStrategy,
                maxSize, localTtl, localNullTtl, localNullTtlDivisor, version, dynamic, dynamicActive,
                centralTtl, centralNullTtl, failOpen, source, configVersion, updatedAtMillis, updatedBy,
                reason, consistentWithDeclared, undeclared, orphan, broken, casSupported);
    }

    /**
     * @param orphan 是否为孤儿配置
     * @return 新视图
     */
    public StrategyView withOrphan(boolean orphan) {
        return new StrategyView(namespace, description, declaredDescription, activeStrategy, declaredStrategy,
                maxSize, localTtl, localNullTtl, localNullTtlDivisor, version, dynamic, dynamicActive,
                centralTtl, centralNullTtl, failOpen, source, configVersion, updatedAtMillis, updatedBy,
                reason, consistentWithDeclared, undeclared, orphan, broken, casSupported);
    }

    /**
     * @param broken 是否解析失败
     * @return 新视图
     */
    public StrategyView withBroken(boolean broken) {
        return new StrategyView(namespace, description, declaredDescription, activeStrategy, declaredStrategy,
                maxSize, localTtl, localNullTtl, localNullTtlDivisor, version, dynamic, dynamicActive,
                centralTtl, centralNullTtl, failOpen, source, configVersion, updatedAtMillis, updatedBy,
                reason, consistentWithDeclared, undeclared, orphan, broken, casSupported);
    }
}
