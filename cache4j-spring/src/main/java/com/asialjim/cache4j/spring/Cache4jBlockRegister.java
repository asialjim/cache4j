package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.strategy.CacheStrategyReader;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 把 {@link Cache4jNamespacePackage} 声明的包扫一遍，找到实现 {@code CacheBlock} 的枚举，
 * 在容器启动时自动注册进 {@link CacheStrategyReader}。
 *
 * <p>执行链路：本类只负责"收集包名 + 注册一个延迟执行的 bean 定义"；
 * 真正的扫描与注册发生在 {@link Cache4jBlockPackage#init()}，那是 bean 初始化阶段，
 * 此时容器里的 {@code CacheStrategyReader} 已经就位。</p>
 *
 * <h2>为什么构造器参数是 {@link BeanFactory} 而不是 {@code ApplicationContext}</h2>
 * <p>{@link ImportBeanDefinitionRegistrar} 由 Spring 内部实例化，构造器参数受
 * {@code BeanUtils.getResolvableConstructor} 约束，只接受 {@code Environment}、
 * {@code BeanFactory}、{@code ResourceLoader}、{@code ClassLoader} 或全部无参。
 * 换成 {@code ApplicationContext} 会让本类无法被构造，容器启动即失败。</p>
 *
 * <p>{@code BeanFactory} 接口本身没有"按类型查 bean 名"的方法，需要转成
 * {@link ListableBeanFactory}——Spring 注入的实际类型就是它。</p>
 *
 * @author Asial Jim
 */
@ComponentScan
@RequiredArgsConstructor
public class Cache4jBlockRegister implements ImportBeanDefinitionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(Cache4jBlockRegister.class);

    /**
     * 递归查找元注解时允许的最大深度。
     *
     * <p>自定义注解的元注解互相引用时会无限递归，这个上限把它挡住。</p>
     */
    private static final int MAX_META_DEPTH = 8;

    private final BeanFactory beanFactory;

    @Override
    public void registerBeanDefinitions(
            @SuppressWarnings("NullableProblems") AnnotationMetadata metadata,
            @SuppressWarnings("NullableProblems") BeanDefinitionRegistry registry
    ) {
        //noinspection ConstantValue
        if (Objects.isNull(metadata) || Objects.isNull(registry))
            return;

        Set<String> packages = new HashSet<>();
        collectPackages(metadata, packages);
        if (packages.isEmpty()) {
            // 没有 @Cache4jNamespacePackage 时静默跳过：这是正常情形（本模块可以只用 CacheManager，不扫声明）
            log.debug("[{}]-{}-{}", "缓存块声明注册", "包收集", "未发现 @Cache4jNamespacePackage 标记，跳过");
            return;
        }

        // 只查 bean 定义，不触发实例化（第三个参数 allowEagerInit=false）
        ListableBeanFactory listable = (ListableBeanFactory) beanFactory;
        String[] names = listable.getBeanNamesForType(CacheStrategyReader.class, true, false);
        //noinspection ConstantValue
        if (Objects.isNull(names) || names.length == 0) {
            log.warn("[{}]-{}-{}", "缓存块声明注册", "包收集",
                    "Spring容器内不存在 CacheStrategyReader 实例，无法注册；声明包=" + packages);
            return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(Cache4jBlockPackage.class);
        builder.addConstructorArgValue(packages);
        builder.addConstructorArgReference(names[0]);
        builder.setInitMethodName("init");
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        String beanName = "inner" + metadata.getClassName() + "Cache4jBlocks";
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    // ------------------------------------------------------------------ 包收集

    private void collectPackages(AnnotationMetadata metadata, Set<String> packages) {
        String name = metadata.getClassName();
        Class<?> metaClass;
        try {
            metaClass = Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            log.warn("[{}]-{}-{}", "缓存块声明注册", "包收集", "找不到类:" + name + "，原因=" + e);
            return;
        }
        collectPackages(metaClass, packages);
    }

    private void collectPackages(Class<?> metaClass, Set<String> packages) {
        if (metaClass == null || Object.class.equals(metaClass)) {
            return;
        }
        // 父类也要看：注解可能标在基类上。接口的 getSuperclass() 返回 null，必须判空
        collectPackages(metaClass.getSuperclass(), packages);

        for (Annotation annotation : metaClass.getAnnotations()) {
            Cache4jNamespacePackage declared = find(annotation, 0);
            if (declared == null) {
                continue;
            }
            String[] explicit = declared.value();
            if (explicit != null && explicit.length > 0) {
                Collections.addAll(packages, explicit);
            } else {
                Package owner = metaClass.getPackage();
                if (owner != null) {
                    packages.add(owner.getName());
                }
            }
        }
    }

    /**
     * 在注解及其元注解里找 {@link Cache4jNamespacePackage}。
     *
     * <p>必须遍历<strong>全部</strong>元注解——组合注解里目标注解未必是第一个。
     * 原实现把 {@code return} 写在循环体内，等于只看第一个元注解。</p>
     *
     * @param annotation 待检查的注解
     * @param depth      当前递归深度
     * @return 找到的注解实例；没有返回 {@code null}
     */
    private Cache4jNamespacePackage find(Annotation annotation, int depth) {
        if (annotation == null || depth > MAX_META_DEPTH) {
            return null;
        }
        if (annotation instanceof Cache4jNamespacePackage found) {
            return found;
        }
        String name = annotation.annotationType().getName();
        // JDK 元注解（@Retention / @Target ...）不会携带业务标记，也不必再往里递归
        if (name.startsWith("java.lang.annotation")) {
            return null;
        }
        for (Annotation meta : annotation.annotationType().getAnnotations()) {
            Cache4jNamespacePackage found = find(meta, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
