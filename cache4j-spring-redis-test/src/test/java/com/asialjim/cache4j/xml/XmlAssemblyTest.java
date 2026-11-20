package com.asialjim.cache4j.xml;

import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 纯 {@code spring-context.xml} 装配：不引入 Spring Boot，也不用任何自动配置机制。
 *
 * <p>验证的是"使用者照抄那份 XML 能不能跑起来"——包括声明扫描、管理器、Spring Cache 桥接。</p>
 *
 * @author Asial Jim
 */
class XmlAssemblyTest {

    @Test
    @DisplayName("XML 装配：声明被扫描到、管理器与 CacheManager 都在、@Cacheable 可用")
    void xmlContextAssemblesWholeChain() {
        try (ClassPathXmlApplicationContext context =
                     new ClassPathXmlApplicationContext("cache4j-context.xml")) {
            TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
            assertEquals(Set.of("xml:demo"), manager.names());

            CacheManager springCacheManager = context.getBean("cacheManager", CacheManager.class);
            assertNotNull(springCacheManager.getCache("xml:demo"),
                    "Spring 侧必须能拿到这个缓存，否则 @Cacheable 不生效");
        }
    }
}
