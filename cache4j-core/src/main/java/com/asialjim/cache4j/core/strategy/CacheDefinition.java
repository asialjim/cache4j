package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.Objects;

/**
 * 一条命名缓存的装配描述：本地配置 + 中央配置。
 *
 * <p>三处 namespace（自身、local、central）必须一致，否则装配必然错位。</p>
 *
 * @param namespace            缓存名
 * @param local                本地配置
 * @param central              中央配置
 * @param localNullTtlDivisor  本地空值副本的除子 N（默认 10）：
 *                             {@code localNullTtl = min(localTtl, centralNullTtl / N)}
 * @author Asial Jim
 */
public record CacheDefinition(String namespace, LocalCacheSpec local, CentralCacheSpec central,
                              com.asialjim.cache4j.core.CacheStrategy strategy,
                              int localNullTtlDivisor) {

    /**
     * 兼容构造器（策略由 local + central 合成，中央侧字段取真实值）。
     */
    public CacheDefinition(String namespace, LocalCacheSpec local, CentralCacheSpec central,
                           int localNullTtlDivisor) {
        this(namespace, local, central, synthesize(local, central), localNullTtlDivisor);
    }

    private static com.asialjim.cache4j.core.CacheStrategy synthesize(
            LocalCacheSpec local, CentralCacheSpec central) {
        return com.asialjim.cache4j.core.CacheStrategy.of(local, central);
    }

    /**
     * 本地空值 TTL 除子的默认值。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = 10;

    /**
     * 紧凑构造器：非空 + namespace 一致性。
     */
    /**
     * 便捷构造：除子取默认值。
     *
     * @param namespace 缓存名
     * @param local     本地配置
     * @param central   中央配置
     */
    public CacheDefinition(String namespace, LocalCacheSpec local, CentralCacheSpec central) {
        this(namespace, local, central, DEFAULT_NULL_TTL_DIVISOR);
    }

    public CacheDefinition {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(central, "central");
        if (!namespace.equals(local.namespace())) {
            throw invalid("namespace 与 local spec 不一致：" + namespace + " vs " + local.namespace());
        }
        if (!namespace.equals(central.namespace())) {
            throw invalid("namespace 与 central spec 不一致：" + namespace + " vs " + central.namespace());
        }
        if (localNullTtlDivisor < 1) {
            throw invalid("localNullTtlDivisor 必须 >= 1：" + localNullTtlDivisor);
        }
    }

    /**
     * 便捷工厂：namespace 取自本地配置。
     *
     * @param local   本地配置
     * @param central 中央配置
     * @return 描述
     */
    public static CacheDefinition of(LocalCacheSpec local, CentralCacheSpec central) {
        return new CacheDefinition(local.namespace(), local, central);
    }

    /**
     * 便捷工厂：显式指定本地空值 TTL 除子。
     *
     * @param local                本地配置
     * @param central              中央配置
     * @param localNullTtlDivisor  除子
     * @return 描述
     */
    public static CacheDefinition of(LocalCacheSpec local, CentralCacheSpec central,
                                     int localNullTtlDivisor) {
        return new CacheDefinition(local.namespace(), local, central, localNullTtlDivisor);
    }

    /**
     * 整体校验（中央配置在构造期已校验，这里补本地配置的完整校验）。
     */
    public void validate() {
        local.validate();
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
