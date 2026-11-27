package com.asialjim.cache4j.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"这个配置类管辖的缓存声明在哪些包下"。
 *
 * <p>被 {@link Cache4jBlockRegister} 读取：它会把这里声明的包交给
 * {@link CacheBlockScanner}，扫出实现 {@code CacheBlock} 的枚举并注册进策略数据源。</p>
 *
 * <h2>两种用法</h2>
 * <pre>{@code
 * // ① 不写包名：取标注类所在的包（要求枚举与配置类同包）
 * @Configuration
 * @Cache4jNamespacePackage
 * @Import(Cache4jBlockRegister.class)
 * class CacheConfig { }
 *
 * // ② 显式指定：枚举可以散落在多个包里
 * @Configuration
 * @Cache4jNamespacePackage({"com.example.cache.user", "com.example.cache.order"})
 * &#064;Import(Cache4jBlockRegister.class)
 * class CacheConfig { }
 * }</pre>
 *
 * <p>默认行为之所以是"标注类所在的包"，是因为最常见的情形就是配置类与枚举同包。
 * 但业务模块往往有多个缓存包，这时显式列出比"每个包放一个配置类"务实得多。</p>
 *
 * @author Asial Jim
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cache4jNamespacePackage {

    /**
     * 要扫描的包名。
     *
     * <p>留空表示取标注类所在的包。</p>
     *
     * @return 包名数组
     */
    String[] value() default {};
}
