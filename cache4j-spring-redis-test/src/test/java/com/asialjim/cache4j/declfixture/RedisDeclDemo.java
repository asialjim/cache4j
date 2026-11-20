package com.asialjim.cache4j.declfixture;

import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;

/**
 * 枚举声明驱动测试用的声明：三种策略各一条。
 *
 * @author Asial Jim
 */
@CacheNamespace
public enum RedisDeclDemo implements CacheStrategyDecl {

    /**
     * 静态本地缓存。
     */
    STATIC("decl:static", LocalStrategy.STATIC, "静态本地缓存（声明驱动测试）"),

    /**
     * 禁用本地缓存，只走中央。
     */
    NONE("decl:none", LocalStrategy.NONE, "只走中央缓存（声明驱动测试）"),

    /**
     * 动态本地缓存。
     */
    DYNAMIC("decl:dynamic", LocalStrategy.DYNAMIC, "动态本地缓存（声明驱动测试）");

    private final String namespace;
    private final LocalStrategy strategy;
    private final String description;

    RedisDeclDemo(String namespace, LocalStrategy strategy, String description) {
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
