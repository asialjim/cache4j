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
 * @param namespace 缓存名
 * @param local     本地配置
 * @param central   中央配置
 * @author Asial Jim
 */
public record CacheDefinition(String namespace, LocalCacheSpec local, CentralCacheSpec central) {

    /**
     * 紧凑构造器：非空 + namespace 一致性。
     */
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
     * 整体校验（中央配置在构造期已校验，这里补本地配置的完整校验）。
     */
    public void validate() {
        local.validate();
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
