package com.asialjim.cache4j.spring;

import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 装配（<strong>纯 Spring Framework，不依赖 Spring Boot</strong>）。
 *
 * <p>两种用法：</p>
 * <ol>
 *   <li>只想拿到二级缓存对象：{@code @Import(Cache4jSpringConfiguration.class)}，注入
 *       {@link TwoLevelCacheManager}；</li>
 *   <li>想用 {@code @Cacheable} 注解：{@code @Import(Cache4jCachingConfiguration.class)}，
 *       它会注册名为 {@code cacheManager} 的 Bean 并开启 {@code @EnableCaching}。</li>
 * </ol>
 *
 * <p><strong>没有 {@code @ConditionalOnMissingBean}</strong>（那是 Boot 的能力）：若你自己已定义
 * {@code cacheManager} / {@code KeyGenerator} / {@code CacheErrorHandler}，就<strong>不要</strong>
 * import 这两个配置类，直接 {@code new} 对应的适配类自行装配。</p>
 *
 * @author Asial Jim
 */
@Configuration(proxyBeanMethods = false)
public class Cache4jSpringConfiguration {

    /**
     * @param options 配置
     * @return 二级缓存管理器（容器关闭时自动关闭）
     */
    @Bean(destroyMethod = "close")
    public TwoLevelCacheManager cache4jCacheManager(Cache4jSpringOptions options) {
        return TwoLevelCacheManager.create(options);
    }

    /**
     * @return 默认 key 生成器
     */
    @Bean
    public KeyGenerator cache4jKeyGenerator() {
        return new Cache4jKeyGenerator();
    }

    /**
     * @param options 配置
     * @return 默认错误处理器（按 {@code failOpen} 降级）
     */
    @Bean
    public CacheErrorHandler cache4jCacheErrorHandler(Cache4jSpringOptions options) {
        boolean failOpen = options.definitions().isEmpty()
                || options.definitions().get(0).central().failOpen();
        return new Cache4jCacheErrorHandler(failOpen);
    }

}
