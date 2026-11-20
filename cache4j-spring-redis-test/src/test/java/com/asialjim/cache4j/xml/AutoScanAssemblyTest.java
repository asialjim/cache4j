package com.asialjim.cache4j.xml;

import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.spring.Cache4jAutoScanConfiguration;
import com.asialjim.cache4j.spring.Cache4jSpringOptions;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 组件扫描装配：{@code @Import(Cache4jAutoScanConfiguration.class)} 扫
 * {@code com.asialjim.cache4j} 包，把 classpath 上存在的 {@code cache4j-spring-redis} 配置类
 * 一并注册——这就是纯 Spring 下"引了依赖就生效"。
 *
 * @author Asial Jim
 */
class AutoScanAssemblyTest {

    @Test
    @DisplayName("扫到 cache4j-spring-redis 的配置类：provider 自动就位，整条链装配完成")
    void scanFindsRedisConfiguration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RedisConnectionFactory.class, AutoScanAssemblyTest::fakeFactory);
            context.register(Cache4jAutoScanConfiguration.class, OptionsConfig.class);
            context.refresh();

            assertNotNull(context.getBean("cache4jCentralProvider"),
                    "cache4j-spring-redis 在 classpath 上，扫描应当自动注册 provider");
            CentralCacheProvider provider = context.getBean(CentralCacheProvider.class);
            assertSame(context.getBean("cache4jCentralProvider"), provider);

            TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
            assertEquals(java.util.Set.of("xml:demo"), manager.names());

            CacheManager springCacheManager = context.getBean("cacheManager", CacheManager.class);
            assertNotNull(springCacheManager.getCache("xml:demo"));
        }
    }

    /**
     * 业务侧只需要给配置（策略声明包），其余全自动。
     */
    @Configuration(proxyBeanMethods = false)
    static class OptionsConfig {

        @Bean
        public Cache4jSpringOptions cache4jOptions(CentralCacheProvider provider) {
            return Cache4jSpringOptions.builder()
                    .centralProvider(provider, false)
                    .declPackages(List.of("com.asialjim.cache4j.xmlfixture"))
                    .build();
        }
    }

    private static RedisConnectionFactory fakeFactory() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(
                RedisConnectionFactory.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, args) -> "getConvertPipelineAndTxResults".equals(method.getName()) ? true : null);
    }
}
