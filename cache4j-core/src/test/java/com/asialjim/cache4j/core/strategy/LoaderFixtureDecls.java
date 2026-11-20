package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalStrategy;

/**
 * 供 {@link CacheStrategyLoaderTest} 使用的声明枚举（由 test resources 下的
 * {@code META-INF/services} 文件引用）。
 *
 * @author Asial Jim
 */
public enum LoaderFixtureDecls implements CacheStrategyDecl {

    /**
     * 静态本地缓存。
     */
    USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情"),

    /**
     * 仅中央。
     */
    ORDER_SNAPSHOT("order:snapshot", LocalStrategy.NONE, "订单快照，仅中央");

    private final String namespace;
    private final LocalStrategy strategy;
    private final String description;

    LoaderFixtureDecls(String namespace, LocalStrategy strategy, String description) {
        this.namespace = namespace;
        this.strategy = strategy;
        this.description = description;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public LocalStrategy getStrategy() {
        return strategy;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
