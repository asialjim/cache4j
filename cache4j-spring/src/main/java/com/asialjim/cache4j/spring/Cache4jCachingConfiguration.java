package com.asialjim.cache4j.spring;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 装配 Spring Cache 桥接并开启 {@code @Cacheable} 支持。
 *
 * <p>用 {@code @Import(Cache4jCachingConfiguration.class)} 即可（它已经带上
 * {@code Cache4jSpringConfiguration}）：容器里会得到 {@link TwoLevelCacheManager}、
 * 名为 {@code cacheManager} 的 {@link CacheManager}、默认 {@link KeyGenerator} 与
 * {@link CacheErrorHandler}。</p>
 *
 * <p><strong>业务自己定义 {@code CacheManager} / {@code CachingConfigurer} 时不要 import 本类</strong>
 * —— 纯 Spring 没有 {@code @ConditionalOnMissingBean}，重复定义会直接冲突。</p>
 *
 * @author Asial Jim
 */
@EnableCaching
@Configuration(proxyBeanMethods = false)
@Import(Cache4jSpringConfiguration.class)
public class Cache4jCachingConfiguration {

    /**
     * @param manager 二级缓存管理器
     * @return Spring CacheManager 适配器（Bean 名固定为 {@code cacheManager}）
     */
    @Bean("cacheManager")
    public CacheManager cacheManager(TwoLevelCacheManager manager) {
        return new SpringCacheManagerAdapter(manager);
    }

    /**
     * 兜底的 {@link CachingConfigurer}：把 key 生成器与错误处理器接上。
     *
     * <p>Spring 全局只允许一个 {@code CachingConfigurer}；业务自己提供时不要 import 本配置。</p>
     *
     * @param keyGenerator key 生成器
     * @param errorHandler 错误处理器
     * @return 配置器
     */
    @Bean
    public CachingConfigurer cache4jCachingConfigurer(KeyGenerator keyGenerator,
                                                      CacheErrorHandler errorHandler) {
        return new CachingConfigurer() {
            @Override
            public KeyGenerator keyGenerator() {
                return keyGenerator;
            }

            @Override
            public CacheErrorHandler errorHandler() {
                return errorHandler;
            }
        };
    }
}
