package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheStrategy;

import com.asialjim.cache4j.core.DynamicSpec;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 内建默认：策略仓库没有该 namespace 的配置、且声明只给了"策略类型"时，用这套值补全参数。
 *
 * <p>只有<strong>一套</strong>默认（不开档位）。值的含义见 {@code docs/STRATEGY.md} §4.3；
 * 数值调整随热 key 判定改造一起切换（当前仍沿用 {@link VersionSpec#defaults()}
 * 与 {@link DynamicSpec#defaults()} 的取值）。</p>
 *
 * @author Asial Jim
 */
public final class BuiltinDefaults {

    /**
     * 本地条目上限。
     */
    public static final long MAX_SIZE = 1_000L;

    /**
     * 本地非空副本存活。
     */
    public static final Duration LOCAL_TTL = Duration.ofSeconds(30);

    /**
     * 中央非空存活。
     */
    public static final Duration CENTRAL_TTL = Duration.ofMinutes(30);

    /**
     * 中央空值存活；同时决定本地负副本上限（{@code min(localTtl, centralNullTtl / N)}）。
     */
    public static final Duration CENTRAL_NULL_TTL = Duration.ofSeconds(30);

    /**
     * 本地空值 TTL 除数：{@code localNullTtl = min(localTtl, centralNullTtl / N)}。
     */
    public static final int LOCAL_NULL_TTL_DIVISOR = 10;

    private BuiltinDefaults() {
    }

    /**
     * 按"命名空间 + 策略类型"生成一份完整配置。
     *
     * @param namespace 命名空间
     * @param strategy  策略
     * @return 装配定义
     */
    public static CacheDefinition definitionOf(String namespace, LocalStrategy strategy) {
        CacheStrategy built = switch (strategy) {
            case NONE -> CacheStrategy.noLocal(namespace);
            case STATIC -> CacheStrategy.staticCache(namespace, MAX_SIZE, LOCAL_TTL);
            case DYNAMIC -> CacheStrategy.dynamicCache(namespace, MAX_SIZE, LOCAL_TTL,
                    VersionSpec.defaults(), DynamicSpec.defaults());
        };
        return CacheDefinition.of(built.withCentral(CENTRAL_TTL, CENTRAL_NULL_TTL, true));
    }
}
