package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.CacheStrategy;

import com.asialjim.cache4j.central.InMemoryCentralCacheProvider;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.CacheStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 纯 Spring Framework 装配测试：{@code @Cacheable} 真正走二级缓存。
 *
 * <p>不涉及 Spring Boot：用手写 {@code @Configuration} + {@link AnnotationConfigApplicationContext}
 * 启动容器，中央缓存用内存实现。</p>
 *
 * @author Asial Jim
 */
class Cache4jSpringConfigurationTest {

    private static final String NS = "user:detail";

    /**
     * 静态计数器：Spring 用 CGLIB 代理 {@code @Bean} 实例（Objenesis 不走构造器），
     * 所以被代理对象的实例字段会是 null —— 计数只能放在静态位置。
     */
    private static final AtomicInteger LOAD_CALLS = new AtomicInteger();

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        LOAD_CALLS.set(0);
        context = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("@Cacheable 命中后不再执行方法体")
    void cacheableUsesTwoLevelCache() {
        UserService service = context.getBean(UserService.class);
        assertEquals("user-42", service.find("42"));
        assertEquals("user-42", service.find("42"));
        assertEquals("user-42", service.find("42"));
        assertEquals(1, LOAD_CALLS.get(), "方法体只应执行一次");
    }

    @Test
    @DisplayName("容器里注册了名为 cacheManager 的 Spring CacheManager")
    void exposesCacheManagerBean() {
        CacheManager cacheManager = context.getBean("cacheManager", CacheManager.class);
        assertNotNull(cacheManager.getCache(NS));
        assertEquals(java.util.List.of(NS), java.util.List.copyOf(cacheManager.getCacheNames()));

        TwoLevelCacheManager twoLevel = context.getBean(TwoLevelCacheManager.class);
        assertSame(twoLevel, ((SpringCacheManagerAdapter) cacheManager).unwrap());
    }

    /**
     * 测试容器配置。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(Cache4jCachingConfiguration.class)
    static class TestConfig {

        @Bean
        Cache4jSpringOptions cache4jSpringOptions() {
            return Cache4jSpringOptions.builder()
                    .centralProvider(new InMemoryCentralCacheProvider(), true)
                    .cache(CacheDefinition.of(
                            CacheStrategy.staticCache(NS, 100, Duration.ofMinutes(1))
                                    .withCentral(Duration.ofMinutes(10), Duration.ZERO, true)))
                    .build();
        }

        @Bean
        UserService userService() {
            return new UserService();
        }
    }

    /**
     * 被缓存的业务 bean。
     */
    @Component
    static class UserService {

        @Cacheable(cacheNames = NS, key = "#id")
        String find(String id) {
            LOAD_CALLS.incrementAndGet();
            return "user-" + id;
        }
    }
}
