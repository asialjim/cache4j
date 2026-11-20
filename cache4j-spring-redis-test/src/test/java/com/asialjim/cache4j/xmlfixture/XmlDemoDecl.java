package com.asialjim.cache4j.xmlfixture;

import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;

/**
 * XML 装配测试用的声明枚举（靠 {@code @CacheNamespace} 包扫描发现）。
 *
 * @author Asial Jim
 */
@CacheNamespace
public enum XmlDemoDecl implements CacheStrategyDecl {

    /**
     * XML 装配测试。
     */
    DEMO("xml:demo", LocalStrategy.STATIC, "XML 装配测试");

    private final String namespace;
    private final LocalStrategy strategy;
    private final String description;

    XmlDemoDecl(String namespace, LocalStrategy strategy, String description) {
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
