package com.asialjim.cache4j.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 注解 + 包扫描发现 {@link CacheStrategyDecl} 枚举。
 *
 * <p>放在 Spring 适配层而不是核心：包扫描要遍历 classpath，而 native image 下运行期没有
 * classpath 目录可扫；Spring 自带成熟实现，没必要在核心重造。</p>
 *
 * <p><strong>关键细节</strong>：{@link ClassPathScanningCandidateComponentProvider} 基于 ASM
 * 读元数据判断候选，<strong>默认不加载类</strong>，产出的是类名字符串。所以扫到之后必须自己
 * {@link ClassUtils#forName} 加载，才能交给转换链路取枚举常量。</p>
 *
 * @author Asial Jim
 */
public final class SpringCacheDeclScanner {

    private static final Logger LOG = LoggerFactory.getLogger(SpringCacheDeclScanner.class);

    private final ClassLoader classLoader;

    /**
     * @param classLoader 类加载器；{@code null} 时使用默认类加载器
     */
    public SpringCacheDeclScanner(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    /**
     * 扫描若干包下带 {@link CacheNamespace} 的枚举。
     *
     * @param basePackages 基础包（可空）
     * @return 实现类（去重、保持扫描顺序）
     */
    public List<Class<?>> scan(List<String> basePackages) {
        if (basePackages == null || basePackages.isEmpty()) {
            return List.of();
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CacheNamespace.class));

        Set<Class<?>> found = new LinkedHashSet<>();
        for (String basePackage : basePackages) {
            List<Class<?>> inPackage = new ArrayList<>();
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                String className = candidate.getBeanClassName();
                Class<?> type = load(className, basePackage);
                if (!type.isEnum()) {
                    throw new CacheException(CacheResCode.INVALID_SPEC,
                            "Spring 扫描：" + basePackage + "：" + className
                                    + " 标注了 @CacheNamespace 但不是枚举，无法表达策略集合");
                }
                if (!CacheStrategyDecl.class.isAssignableFrom(type)) {
                    throw new CacheException(CacheResCode.INVALID_SPEC,
                            "Spring 扫描：" + basePackage + "：" + className + " 未实现 CacheStrategyDecl");
                }
                inPackage.add(type);
            }
            if (inPackage.isEmpty()) {
                LOG.debug("[{}]-{}-{}", "缓存装配", "声明", "Spring 扫描：" + basePackage + " 未发现 @CacheNamespace 枚举");
            } else {
                LOG.debug("[{}]-{}-{}", "缓存装配", "声明", "Spring 扫描：" + basePackage + " 发现 " + inPackage.size() + " 个声明枚举");
            }
            found.addAll(inPackage);
        }
        return new ArrayList<>(found);
    }

    private Class<?> load(String className, String basePackage) {
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new CacheException(CacheResCode.INVALID_SPEC,
                    "Spring 扫描：" + basePackage + "：" + className + " 加载失败", e);
        }
    }
}
