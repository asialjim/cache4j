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
        CacheStrategyProperty property,
        LocalStrategy declaredStrategy,
        Source source,
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason
) {

    /**
     * 便捷构造：从展开参数转成 {@link CacheStrategyProperty} 再落入 record。
     *
     * <p>只做转换，<strong>不重复存字段</strong>——记录里唯一的配置来源仍是
     * {@link CacheStrategyProperty}。{@code null} 保持 {@code null}（表示未设置）。</p>
     *
     * @param namespace           命名空间
     * @param description         说明
     * @param strategy            生效的本地策略
     * @param declaredStrategy    声明值（只用于审计与展示）
     * @param maxSize             本地条目上限
     * @param localTtl            本地非空副本存活
     * @param localNullTtlDivisor 本地空值副本除数
     * @param version             版本对账参数
     * @param dynamic             {@code DYNAMIC} 参数
     * @param centralTtl          中央非空存活
     * @param centralNullTtl      中央空值存活
     * @param failOpen            中央不可用是否降级
     * @param configVersion       配置版本号
     * @param updatedAtMillis     最近变更时间
     * @param updatedBy           变更人
     * @param reason              变更原因
     * @param source              来源
     */
    public NamespacePolicy(String namespace, String description, LocalStrategy strategy,
                           LocalStrategy declaredStrategy, long maxSize, Duration localTtl,
                           int localNullTtlDivisor, LocalCacheSpec.VersionSpec version,
                           LocalCacheSpec.DynamicSpec dynamic, Duration centralTtl,
                           Duration centralNullTtl, boolean failOpen, long configVersion,
                           long updatedAtMillis, String updatedBy, String reason, Source source) {
        this(CacheStrategyProperty.ofRaw(namespace, description, strategy, maxSize, localTtl,
                        localNullTtlDivisor, version, dynamic, centralTtl, centralNullTtl, failOpen),
                declaredStrategy, source, configVersion, updatedAtMillis, updatedBy, reason);
    }

    /**
     * @return 命名空间
     */
    public String namespace() {
        return property.getNamespace();
    }

    /**
     * @return 用途说明
     */
    public String description() {
        return property.getDescription();
    }

    /**
     * @return 生效的本地策略
     */
    public LocalStrategy strategy() {
        return property.strategy();
    }

    /**
     * @return 本地条目上限（已经过默认补齐）
     */
    public long maxSize() {
        return property.maxSize();
    }

    /**
     * @return 本地非空副本存活
     */
    public Duration localTtl() {
        return property.localTtlDuration();
    }

    /**
     * @return 本地空值副本除数
     */
    public int localNullTtlDivisor() {
        return property.nullTtlDivisor();
    }

    /**
     * @return 版本对账参数
     */
    public LocalCacheSpec.VersionSpec version() {
        return property.versionSpec();
    }

    /**
     * @return {@code DYNAMIC} 参数；非 DYNAMIC 返回 {@code null}
     */
    public LocalCacheSpec.DynamicSpec dynamic() {
        return getStrategyNameIsDynamic() ? property.dynamicSpec() : null;
    }

    /**
     * @return 中央非空存活
     */
    public Duration centralTtl() {
        return property.centralTtlDuration();
    }

    /**
     * @return 中央空值存活
     */
    public Duration centralNullTtl() {
        return property.centralNullTtlDuration();
    }

    /**
     * @return 中央不可用是否降级
     */
    public boolean failOpen() {
        return property.failOpenValue();
    }

    private boolean getStrategyNameIsDynamic() {
        return LocalStrategy.DYNAMIC.name().equals(property.getLocalStrategy());
    }

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
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(property.getNamespace(), "namespace");
        Objects.requireNonNull(property.getLocalStrategy(), "strategy");
        Objects.requireNonNull(source, "source");
        if (!LocalStrategy.NONE.name().equals(property.getLocalStrategy())) {
            if (property.getLocalMaxSize() == null || property.getLocalMaxSize() <= 0L) {
                throw invalid("maxSize 必须大于 0：" + property.getLocalMaxSize());
            }
            if (property.getLocalTtl() == null || property.getLocalTtl() <= 0L) {
                throw invalid("localTtl 必须为正：" + property.getLocalTtl());
            }
            if (property.getVersionCheckInterval() == null) {
                throw invalid("STATIC / DYNAMIC 的 version 不允许为 null");
            }
        }
        // 注意：紧凑构造器里字段尚未赋值，不能调用实例方法（它们读的是 this.property）。
        if (LocalStrategy.DYNAMIC.name().equals(property.getLocalStrategy())
                && property.getHotThresholdQps() == null) {
            throw invalid("DYNAMIC 必须提供 hot key 参数");
        }
        if (property.getLocalNullTtlDivisor() == null || property.getLocalNullTtlDivisor() < 1) {
            throw invalid("localNullTtlDivisor 必须 >= 1：" + property.getLocalNullTtlDivisor());
        }
        if (property.getCentralTtl() == null || property.getCentralTtl() <= 0L) {
            throw invalid("centralTtl 必须为正：" + property.getCentralTtl());
        }
        if (property.getCentralNullTtl() != null && property.getCentralNullTtl() < 0L) {
            throw invalid("centralNullTtl 不能为负：" + property.getCentralNullTtl());
        }
    }

    /**
     * 转成装配定义。
     *
     * @return 装配定义
     */
    public CacheDefinition toDefinition() {
        // 负副本的存活时长在这里算好（这里同时知道中央 nullTtl 与除子），
        // 本地缓存的写路径直接用，不必运行时回头读中央配置
        Duration nullTtl = LocalCacheSpec.deriveNullTtl(localTtl(), centralNullTtl(), localNullTtlDivisor());
        LocalCacheSpec local = switch (strategy()) {
            case NONE -> LocalCacheSpec.noLocal(namespace());
            case STATIC -> LocalCacheSpec.staticCache(namespace(), maxSize(), localTtl(), nullTtl, version());
            case DYNAMIC -> LocalCacheSpec.dynamicCache(namespace(), maxSize(), localTtl(), nullTtl, version(), dynamic());
        };
        CentralCacheSpec central = CentralCacheSpec.builder(namespace())
                .ttl(centralTtl())
                .nullTtl(centralNullTtl())
                .failOpen(failOpen())
                .build();
        CacheDefinition definition = CacheDefinition.of(local, central, localNullTtlDivisor());
        definition.validate();
        return definition;
    }

    /**
     * @return 生效值是否与声明值一致（仅展示用，<strong>不告警</strong>）
     */
    public boolean consistentWithDeclared() {
        // 孤儿配置（数据源有、代码没声明）没有可比对象，视为一致
        return declaredStrategy == null || strategy() == declaredStrategy;
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
