package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.util.ClassUtils;

/**
 * 修正"声明枚举被组件扫描注册成 Bean"之后的实例化方式。
 *
 * <p>业务侧在 {@code component-scan} 里用 include-filter 把 {@code @CacheNamespace} 加进来之后，
 * Spring 默认会拿枚举的构造器去做自动装配（{@code (String, LocalStrategy, String)}），
 * 找不到 {@code String} Bean 直接启动失败。枚举本来就该取常量，不该被 new 出来。</p>
 *
 * <p>这里把所有"实现 {@link CacheStrategyDecl} 的枚举" Bean 定义的实例化改成
 * {@code getEnumConstants()[0]}：一个枚举类只占一个 Bean 位，库再从这个类拿到<strong>全部</strong>
 * 常量（{@code STATIC} / {@code NONE} / {@code DYNAMIC} …）。</p>
 *
 * <p>只碰 {@link CacheStrategyDecl} 的枚举，其他枚举一概不碰。</p>
 *
 * @author Asial Jim
 */
public class Cache4jDeclBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            String className = beanFactory.getBeanDefinition(beanName).getBeanClassName();
            if (className == null || !(beanFactory.getBeanDefinition(beanName) instanceof AbstractBeanDefinition definition)) {
                continue;
            }
            Class<?> type;
            try {
                type = ClassUtils.forName(className, classLoader);
            } catch (Throwable ignored) {
                // 加载不了就不归我们管（可能是其他模块的类）
                continue;
            }
            if (!type.isEnum() || !CacheStrategyDecl.class.isAssignableFrom(type)) {
                continue;
            }
            Object[] constants = type.getEnumConstants();
            if (constants == null || constants.length == 0) {
                continue;
            }
            definition.setInstanceSupplier(() -> constants[0]);
        }
    }
}
