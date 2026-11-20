package com.asialjim.cache4j.core.strategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个实现 {@link CacheStrategyDecl} 的枚举，供框架适配层的包扫描发现。
 *
 * <p>只为"框架适配层的扫描"服务：核心 {@code cache4j-core} 不实现包扫描
 * （native image 下运行期遍历 classpath 不可行）。纯 Java 用户走
 * {@code META-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl} 的文件约定；
 * Spring 适配层用自带的扫描能力处理本注解。</p>
 *
 * @author Asial Jim
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CacheNamespace {

    /**
     * @return 可选补充说明，出现在启动日志里
     */
    String value() default "";
}
