package com.asialjim.cache4j.spring.declfixture;

import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;

/**
 * 端到端测试用的声明枚举（业务侧的典型形态）。
 *
 * @author Asial Jim
 */
@CacheNamespace
public enum DeclaredDemo implements CacheStrategyDecl {

    /**
     * 静态本地缓存。
     */
    DEMO("demo:declared", LocalStrategy.STATIC, "端到端测试声明");

    private final String namespace;
    private final LocalStrategy strategy;
    private final String description;

    DeclaredDemo(String namespace, LocalStrategy strategy, String description) {
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
