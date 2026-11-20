package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.util.ClassUtils;

/**
 * 把"被组件扫描注册进来的声明枚举"换成本库的注册载体 {@link CacheNamespaceDeclarations}。
 *
 * <p>为什么需要它：Spring 扫描命中 {@code @CacheNamespace} 枚举后，会拿枚举的构造器
 * （{@code (String, LocalStrategy, String)}）去做自动装配，找不到 {@code String} Bean 直接
 * 启动失败——枚举本来就该取常量，不该被 new。</p>
 *
 * <p>做法不是"让枚举能被 new"，而是<strong>换掉 Bean 定义的类型</strong>：Bean 名保留，类型改成
 * {@link CacheNamespaceDeclarations}，实例化用 {@code getEnumConstants()}。于是：</p>
 * <ul>
 *   <li>容器里不再有业务枚举 Bean，应用不会误注入它；</li>
 *   <li>枚举的全部常量（{@code STATIC} / {@code NONE} / {@code DYNAMIC} …）一次性被载体持有；</li>
 *   <li>只碰 {@link CacheStrategyDecl} 的<strong>枚举</strong>，其他类一概不动。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public class Cache4jDeclBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (!(beanFactory.getBeanDefinition(beanName) instanceof AbstractBeanDefinition definition)) {
                continue;
            }
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = ClassUtils.forName(className, classLoader);
            } catch (Throwable ignored) {
                // 加载不了就不归我们管
                continue;
            }
            if (!type.isEnum() || !CacheStrategyDecl.class.isAssignableFrom(type)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends CacheStrategyDecl> declarationType = (Class<? extends CacheStrategyDecl>) type;
            definition.setBeanClassName(CacheNamespaceDeclarations.class.getName());
            definition.setInstanceSupplier(() -> new CacheNamespaceDeclarations(declarationType));
        }
    }
}
