package com.asialjim.cache4j.spring;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 组件扫描装配：扫描 {@code com.asialjim.cache4j} 整个包，把 classpath 上<strong>存在</strong>的
 * 实现模块（{@code cache4j-spring-redis} 等）里的 {@code @Configuration} 一并注册。
 *
 * <p>这是纯 Spring Framework 下能达到"按 classpath 自动生效"的唯一正路：没有 Boot 的
 * {@code @ConditionalOnClass}，但 {@code @ComponentScan} 天然只看得见 classpath 上真实存在的类
 * ——没引 {@code cache4j-spring-redis}，就扫不到它的配置类，也就不会去装配 Redis。</p>
 *
 * <p>与 {@link Cache4jCachingConfiguration} 的分工：</p>
 * <ul>
 *   <li>{@code @Import(Cache4jCachingConfiguration.class)}：精确控制，中央 provider 由业务自己给；</li>
 *   <li>{@code @Import(Cache4jAutoScanConfiguration.class)}：一把梭，子模块的配置类自动注册。</li>
 * </ul>
 *
 * <p><strong>二者选一个</strong>：都 import 会重复注册 {@code cacheManager}。</p>
 *
 * @author Asial Jim
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
        basePackages = Cache4jAutoScanConfiguration.LIBRARY_PACKAGE,
        // 本库自己的测试类也在 com.asialjim.cache4j 下，且各自定义 @Bean：
        // 生产 jar 里没有测试类，排除它们只是为了让本库的测试集合保持隔离。
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.asialjim\\.cache4j\\..*\\$.*"))
public class Cache4jAutoScanConfiguration {

    /**
     * 库的根包。
     */
    public static final String LIBRARY_PACKAGE = "com.asialjim.cache4j";
}
