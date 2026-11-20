package com.asialjim.cache4j.xml;

import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.spring.Cache4jAutoScanConfiguration;
import com.asialjim.cache4j.spring.CacheNamespaceDeclarations;
import com.asialjim.cache4j.xmlfixture.XmlDemoDecl;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 组件扫描装配：注册 {@code Cache4jAutoScanConfiguration} 扫 {@code com.asialjim.cache4j} 包，
 * 把 classpath 上存在的 {@code cache4j-spring-redis} 配置类一并注册——这就是纯 Spring 下
 * "引了依赖就生效"。
 *
 * <p>业务侧只提供两样东西：{@code RedisConnectionFactory}（客户端自己选）与
 * {@code cache4j.decl.packages}（声明枚举所在包）。{@code cache4jOptions} /
 * {@code cache4jSerializer} 等全部由库的 {@code @Configuration} 自动给。</p>
 *
 * @author Asial Jim
 */
class AutoScanAssemblyTest {

    @Test
    @DisplayName("扫到 cache4j-spring-redis 的配置类：provider 自动就位，整条链装配完成")
    void scanFindsRedisConfiguration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RedisConnectionFactory.class, AutoScanAssemblyTest::fakeFactory);
            context.register(Cache4jAutoScanConfiguration.class);
            // 业务侧只需给两样：连接工厂（上面）与声明扫描包（这里）
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("cache4j.decl.packages", "com.asialjim.cache4j.xmlfixture")));
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

    @Test
    @DisplayName("不配 decl.packages：靠 component-scan 扫 @CacheNamespace 自动发现声明")
    void discoversDeclarationsByComponentScanOnly() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RedisConnectionFactory.class, AutoScanAssemblyTest::fakeFactory);
            context.register(Cache4jAutoScanConfiguration.class, NamespaceScanConfig.class);
            context.refresh();

            TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
            assertEquals(java.util.Set.of("xml:demo"), manager.names(),
                    "枚举被扫进容器后应当自动成为缓存定义，不需要额外配包名");

            // 容器里注册的是载体，不是业务枚举本身
            assertEquals(0, context.getBeansOfType(com.asialjim.cache4j.core.strategy.CacheStrategyDecl.class).size(),
                    "业务枚举不应作为 Bean 暴露给应用");
            assertEquals(1, context.getBeansOfType(CacheNamespaceDeclarations.class).size(),
                    "应当有一个声明载体持有该枚举的 values()");
            CacheNamespaceDeclarations carrier = context.getBean(CacheNamespaceDeclarations.class);
            assertEquals(XmlDemoDecl.class, carrier.declarationType());
            assertEquals(1, carrier.values().size(), "载体持有枚举的全部常量");
        }
    }

    /**
     * 业务侧的常规写法：component-scan 只收 {@code @CacheNamespace}。
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.asialjim.cache4j.xmlfixture",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = CacheNamespace.class))
    static class NamespaceScanConfig {
    }

    private static RedisConnectionFactory fakeFactory() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(
                RedisConnectionFactory.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, args) -> "getConvertPipelineAndTxResults".equals(method.getName()) ? true : null);
    }
}
