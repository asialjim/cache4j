package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.strategy.CacheCandidate;
import com.asialjim.cache4j.core.strategy.CacheDeclTranslator;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一个 {@code @CacheNamespace} 枚举的<strong>注册载体</strong>。
 *
 * <p>Spring 的组件扫描只会注册"被扫到的那个类"，而枚举不能被 {@code new} 出来（
 * 拿它的构造器做自动装配会直接启动失败）。所以扫描命中之后，容器里注册的是本类而不是枚举：
 * 它持有该枚举的<strong>全部常量</strong>（{@code values()}），并已把它们翻译成缓存定义。</p>
 *
 * <p>应用代码不需要、也不应该从容器里取本类——它只是"发现结果的载体"。真正消费它的是
 * {@link Cache4jDefaultOptionsConfiguration}。</p>
 *
 * @author Asial Jim
 */
public final class CacheNamespaceDeclarations {

    private final Class<? extends CacheStrategyDecl> declarationType;

    private final List<CacheStrategyDecl> values;

    private final List<CacheDefinition> definitions;

    /**
     * @param declarationType 声明枚举类型
     */
    public CacheNamespaceDeclarations(Class<? extends CacheStrategyDecl> declarationType) {
        if (!declarationType.isEnum()) {
            throw new IllegalArgumentException("声明载体只接受枚举，实际=" + declarationType.getName());
        }
        this.declarationType = declarationType;
        this.values = Collections.unmodifiableList(
                Arrays.asList(declarationType.getEnumConstants()));
        this.definitions = translate(declarationType, values);
    }

    private static List<CacheDefinition> translate(Class<? extends CacheStrategyDecl> type,
                                                   List<CacheStrategyDecl> values) {
        List<CacheDefinition> result = new ArrayList<>(values.size());
        for (CacheCandidate candidate : new CacheDeclTranslator().translate(type, "组件扫描")) {
            result.add(candidate.definition());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return 声明枚举类型
     */
    public Class<? extends CacheStrategyDecl> declarationType() {
        return declarationType;
    }

    /**
     * @return 该枚举的全部常量（{@code values()}）
     */
    public List<CacheStrategyDecl> values() {
        return values;
    }

    /**
     * @return 由 {@link #values()} 翻译出的缓存定义
     */
    public List<CacheDefinition> definitions() {
        return definitions;
    }

    @Override
    public String toString() {
        return "CacheNamespaceDeclarations[" + declarationType.getName() + ", " + values.size() + " 个常量]";
    }
}
