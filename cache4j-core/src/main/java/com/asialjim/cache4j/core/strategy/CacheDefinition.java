package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.CacheStrategy;

import java.util.Objects;

/**
 * 一条命名缓存的装配描述：<strong>一份完整策略 + 一个装配期参数</strong>。
 *
 * <p>不再是"本地配置 + 中央配置"的容器——那两样已经合并成 {@link CacheStrategy}。
 * 这里只多带一个装配期才需要的派生参数（本地负副本除子）。</p>
 *
 * @param namespace           缓存名
 * @param strategy            完整策略（本地 + 中央）
 * @param localNullTtlDivisor 本地负副本除子 N：{@code min(localTtl, centralNullTtl / N)}
 * @author Asial Jim
 */
public record CacheDefinition(String namespace, CacheStrategy strategy, int localNullTtlDivisor) {

    /**
     * 本地负副本除子的默认值。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = 10;

    public CacheDefinition {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(strategy, "strategy");
        if (!namespace.equals(strategy.namespace())) {
            throw invalid("namespace 与策略不一致：" + namespace + " vs " + strategy.namespace());
        }
        if (localNullTtlDivisor < 1) {
            throw invalid("localNullTtlDivisor 必须 >= 1：" + localNullTtlDivisor);
        }
    }

    /**
     * 便捷工厂：除子取默认值。
     *
     * @param strategy 完整策略
     * @return 装配描述
     */
    public static CacheDefinition of(CacheStrategy strategy) {
        return new CacheDefinition(strategy.namespace(), strategy, DEFAULT_NULL_TTL_DIVISOR);
    }

    /**
     * 便捷工厂：显式指定除子。
     *
     * @param strategy            完整策略
     * @param localNullTtlDivisor 除子
     * @return 装配描述
     */
    public static CacheDefinition of(CacheStrategy strategy, int localNullTtlDivisor) {
        return new CacheDefinition(strategy.namespace(), strategy, localNullTtlDivisor);
    }

    /**
     * 主动校验（策略本身在构造时已校验，这里再走一遍本地视图的边界检查）。
     */
    public void validate() {
        strategy.validate();
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
