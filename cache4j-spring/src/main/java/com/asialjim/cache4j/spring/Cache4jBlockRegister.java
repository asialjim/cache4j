package com.asialjim.cache4j.spring;


import com.asialjim.cache4j.strategy.CacheStrategyReader;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
public class Cache4jBlockRegister implements ImportBeanDefinitionRegistrar {
    private static final Logger log = LoggerFactory.getLogger(Cache4jBlockRegister.class);
    private final ApplicationContext applicationContext;

    @Override
    public void registerBeanDefinitions(
            @SuppressWarnings("NullableProblems") AnnotationMetadata metadata,
            @SuppressWarnings("NullableProblems") BeanDefinitionRegistry registry
    ) {

        //noinspection ConstantValue
        if (Objects.isNull(metadata))
            return;
        Set<String> packages = new HashSet<>();
        scanCacheNamespace(metadata,packages);


        String[] names = applicationContext.getBeanNamesForType(CacheStrategyReader.class);
        //noinspection ConstantValue
        if (Objects.isNull(names) || names.length == 0) {
            log.warn("[{}]-{}-{}","缓存块声明注册","包扫描","Spring容器内不存在CacheStrategyReader 实例，无法注册");
            return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(Cache4jBlockPackage.class);
        builder.addConstructorArgValue(packages);
        builder.addConstructorArgReference(names[0]);
        builder.setInitMethodName("init");
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        String beanName = "inner" +  metadata.getClassName() + "Cache4jBlocks";
        registry.registerBeanDefinition(beanName,beanDefinition);
    }

    private void scanCacheNamespace(AnnotationMetadata metadata, Set<String> packages) {
        Class<?> metaClass;
        String name = metadata.getClassName();
        try {
            metaClass = Class.forName(name);
        } catch (ClassNotFoundException e) {
            log.warn("[{}]-{}-{}", "缓存块声明注册", "包扫描", "找不到类:" + name);
            return;
        }
        scanCacheNamespace(metaClass, packages);
    }

    private void scanCacheNamespace(Class<?> metaClass, Set<String> packages) {
        Class<?> superclass = metaClass.getSuperclass();
        if (!Object.class.equals(superclass))
            scanCacheNamespace(superclass, packages);

        for (Annotation annotation : metaClass.getAnnotations()) {
            boolean b = scanCacheNamespace(annotation);
            if (b)
                packages.add(metaClass.getPackage().getName());
        }
    }

    private boolean scanCacheNamespace(Annotation annotation) {
        if (Objects.isNull(annotation))
            return false;

        if (annotation instanceof Cache4jNamespacePackage) {
            return true;
        }

        if (annotation.annotationType().getName().startsWith("java.lang.annotation"))
            return false;

        Annotation[] annotations = annotation.annotationType().getAnnotations();
        //noinspection ConstantValue
        if (Objects.isNull(annotation))
            return false;

        for (Annotation superAnnotation : annotations) {
            return scanCacheNamespace(superAnnotation);
        }

        return false;
    }

}
