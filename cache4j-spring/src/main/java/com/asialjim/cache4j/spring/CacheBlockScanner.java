package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.strategy.CacheBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描 classpath 下实现了 {@link CacheBlock} 的枚举类型。
 *
 * <h2>只认枚举</h2>
 * <p>{@link CacheBlock} 是接口，理论上任何类都能实现。但对本体系有意义的实现形态只有
 * <strong>枚举</strong>——一个枚举类型里的常量就是"这个模块声明了哪些缓存"的完整清单，
 * 可以被枚举、被校验、被 IDE 跳转。普通类实现它没有任何额外信息，
 * 所以扫描阶段就过滤掉，避免把"实现了一半的东西"带进注册表。</p>
 *
 * <h2>扫描范围</h2>
 * <p>必须显式给出基础包：全 classpath 扫描在 fat jar、模块化、自定义 ClassLoader 下
 * 行为不可预测，且会拖慢启动。给一两个明确的业务包是更可靠的做法。</p>
 *
 * <h2>不注册 Bean</h2>
 * <p>本类只返回 {@code Class} 列表，不往容器里塞任何 bean——枚举本来就无需实例化，
 * 注册成 bean 反而会让"哪个枚举是声明源"变得暧昧。</p>
 *
 * @author Asial Jim
 */
public final class CacheBlockScanner {

    private static final Logger log = LoggerFactory.getLogger(CacheBlockScanner.class);

    private CacheBlockScanner() {
    }
    /**
     * 扫描指定包下的 {@link CacheBlock} 枚举。
     *
     * @param basePackages 基础包，例如 {@code "com.example.cache"}
     * @return 枚举类型列表；按类名排序，保证多次扫描结果稳定
     */
    public static List<Class<? extends CacheBlock>> scan(Set<String> basePackages) {
        if (basePackages == null || basePackages.isEmpty()) {
            throw new IllegalArgumentException("必须显式指定基础包：全 classpath 扫描不可靠且会拖慢启动");
        }

        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(CacheBlock.class));

        Set<String> names = new LinkedHashSet<>();
        for (String basePackage : basePackages) {
            for (BeanDefinition definition : provider.findCandidateComponents(basePackage)) {
                String className = definition.getBeanClassName();
                if (className != null) {
                    names.add(className);
                }
            }
        }

        List<Class<? extends CacheBlock>> found = new ArrayList<>(names.size());
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        for (String className : sorted) {
            Class<?> type = load(className);
            if (type == null || !type.isEnum() || !CacheBlock.class.isAssignableFrom(type)) {
                continue;
            }
            //noinspection unchecked
            found.add((Class<? extends CacheBlock>) type);
        }
        log.debug("[cache-block]-scan-found count={} packages={}", found.size(), List.of(basePackages));
        return found;
    }





    public static Class<?> load(String className) {
        try {
            // 用扫描器自己的 ClassLoader：在 Spring 容器里，业务类通常由应用 ClassLoader 加载
            return Class.forName(className, false, CacheBlockScanner.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            // 扫到了但加载不了：多半是可选依赖缺失。跳过比让整个启动失败合理。
            log.warn("[cache-block]-class-load-failed class={} reason={}", className, e.toString());
            return null;
        }
    }
}
