package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.central.InMemoryCentralCacheProvider;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端：业务只做三件事，缓存体系自动接好。
 *
 * <ol>
 *   <li>引依赖（{@code cache4j-spring} + 中央实现）；</li>
 *   <li>写 {@code @CacheNamespace} 声明枚举，并让 component-scan 收进来；</li>
 *   <li>{@code @Import(Cache4jAutoScanConfiguration.class)} 激活，提供 {@code CentralCacheProvider}。</li>
 * </ol>
 *
 * <p>然后 {@code @Cacheable} 直接可用——枚举被翻译成缓存定义，不需要任何 {@code cache4j.*} 配置。</p>
 *
 * @author Asial Jim
 */
class DeclarationEndToEndTest {

    private static final String NS = "demo:declared";

    private static final AtomicInteger LOAD_CALLS = new AtomicInteger();

    @Test
    @DisplayName("声明枚举 → 缓存定义 → @Cacheable 生效，全程不用配 cache4j.*")
    void declarationDrivesTheWholeChain() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            LOAD_CALLS.set(0);
            context.register(BusinessConfig.class);
            context.refresh();

            // ① 枚举被翻译成缓存定义
            TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
            assertEquals(Set.of(NS), manager.names());

            // ② Spring 侧能看到这个缓存
            assertNotNull(context.getBean("cacheManager", org.springframework.cache.CacheManager.class)
                    .getCache(NS));

            // ③ @Cacheable 真的命中二级缓存：方法体只执行一次
            DeclaredService service = context.getBean(DeclaredService.class);
            assertEquals("value-7", service.load("7"));
            assertEquals("value-7", service.load("7"));
            assertEquals(1, LOAD_CALLS.get(), "第二次应从缓存命中，不再执行方法体");
        }
    }

    /**
     * 业务侧需要写的全部配置。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(Cache4jAutoScanConfiguration.class)
    @ComponentScan(
            basePackages = "com.asialjim.cache4j.spring.declfixture",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = CacheNamespace.class))
    static class BusinessConfig {

        /**
         * @return 中央缓存实现（生产换成 Redis 的 provider）
         */
        @Bean
        CentralCacheProvider centralCacheProvider() {
            return new InMemoryCentralCacheProvider();
        }

        @Bean
        DeclaredService declaredService() {
            return new DeclaredService();
        }
    }

    /**
     * 被缓存的业务方法。
     */
    @Component
    static class DeclaredService {

        @Cacheable(cacheNames = NS, key = "#id")
        String load(String id) {
            LOAD_CALLS.incrementAndGet();
            return "value-" + id;
        }
    }
}
