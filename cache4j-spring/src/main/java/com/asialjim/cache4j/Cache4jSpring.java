package com.asialjim.cache4j;

import com.asialjim.cache4j.spring.Cache4jBlockRegister;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * cache4j 的 Spring 集成入口。
 *
 * <h2>两种触发方式，语义完全一致</h2>
 * <pre>{@code
 * // 注解配置
 * @Configuration
 * @Import(Cache4jSpring.class)
 * class AppConfig { }
 *
 * // XML 配置
 * <cache4j:annotation-driven/>
 * }</pre>
 *
 * <h2>它只做一件事：触发 {@code CacheBlock} 声明扫描</h2>
 * <p>扫描实现 {@code CacheBlock} 的枚举、翻译成策略、注册进 {@code CacheStrategyReader}。
 * 这件事不需要任何业务决策，所以适合自动完成。</p>
 *
 * <p><strong>不装配缓存管理器。</strong> {@code CacheManager} 依赖"本地用什么缓存、中央连哪里、
 * 策略从哪读"——这些全是业务决策，由使用方用 {@code @Bean} 声明：</p>
 * <pre>{@code
 * @Bean
 * CacheManager cacheManager(CacheStrategyReader reader,
 *                           KeyEncoder encoder,
 *                           StaticLocalCache4j.Provider localStatic,
 *                           DynamicLocalCache4j.Provider localDynamic,
 *                           CentralCache4j.Provider central) {
 *     return new Cache4jCacheManager(encoder, reader, localStatic, localDynamic, central);
 * }
 * }</pre>
 *
 * <p>之所以不走"扫 {@code @Component} 自动注册"：那要求本模块猜出上面这些依赖，
 * 猜不到就注入失败、容器起不来。让使用方显式声明，失败了也是一眼看得懂的错误。</p>
 *
 * @author Asial Jim
 */
@Configuration
@ComponentScan
public class Cache4jSpring {
}
