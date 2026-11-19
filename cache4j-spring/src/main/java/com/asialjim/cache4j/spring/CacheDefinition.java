package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.util.Objects;

/**
 * 一条命名缓存的装配描述：本地配置 + 中央配置。
 *
 * @param namespace 缓存名（必须与两个 spec 的 namespace 一致）
 * @param local     本地配置
 * @param central   中央配置
 * @author Asial Jim
 */
public record CacheDefinition(String namespace, LocalCacheSpec local, CentralCacheSpec central) {

    /**
     * 紧凑构造器：三处 namespace 必须一致，否则装配必然错位。
     */
    public CacheDefinition {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(central, "central");
        if (!namespace.equals(local.namespace())) {
            throw new IllegalArgumentException(
                    "namespace 与 local spec 不一致：" + namespace + " vs " + local.namespace());
        }
        if (!namespace.equals(central.namespace())) {
            throw new IllegalArgumentException(
                    "namespace 与 central spec 不一致：" + namespace + " vs " + central.namespace());
        }
    }

    /**
     * 便捷工厂：只给本地策略 + 中央 TTL。
     *
     * @param local   本地配置
     * @param central 中央配置
     * @return 描述
     */
    public static CacheDefinition of(LocalCacheSpec local, CentralCacheSpec central) {
        return new CacheDefinition(local.namespace(), local, central);
    }
}
