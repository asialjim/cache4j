package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.CacheStrategy;

import com.asialjim.cache4j.central.InMemoryCentralCacheProvider;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配来源：程序化定义 / {@code META-INF/services} 文件声明 / 显式传入的枚举，
 * 以及它们之间的冲突检测。
 *
 * @author Asial Jim
 */
class DeclarationAssemblyTest {

    @Test
    @DisplayName("没有程序化定义时，光靠声明也能装配")
    void assemblesFromDeclarationsOnly() {
        try (TwoLevelCacheManager manager = TwoLevelCacheManager.create(
                options().declEnums(AutoDecls.class).build())) {
            assertEquals(Set.of("auto:declared"), manager.names());
            assertTrue(manager.cache("auto:declared").isPresent());
        }
    }

    @Test
    @DisplayName("程序化定义与声明可以共存，各自保留自己的配置")
    void programmaticAndDeclarationCoexist() {
        CacheDefinition programmatic = CacheDefinition.of(
                CacheStrategy.staticCache("manual:cache", 50, Duration.ofSeconds(20))
                        .withCentral(Duration.ofMinutes(5), Duration.ofSeconds(10), true));
        try (TwoLevelCacheManager manager = TwoLevelCacheManager.create(
                options().cache(programmatic).declEnums(AutoDecls.class).build())) {
            assertEquals(Set.of("manual:cache", "auto:declared"), manager.names());
            // 程序化定义保留自己的 maxSize（不被内建默认覆盖）
            assertEquals(50L, manager.cache("manual:cache").orElseThrow().local().cacheStrategy().localMaxSize());
            // 声明走内建默认
            assertEquals(1_000L, manager.cache("auto:declared").orElseThrow().local().cacheStrategy().localMaxSize());
        }
    }

    @Test
    @DisplayName("同一 namespace 来自不同来源：启动失败（不静默取其一）")
    void conflictingSourcesFailFast() {
        CacheDefinition programmatic = CacheDefinition.of(
                CacheStrategy.staticCache("auto:declared", 50, Duration.ofSeconds(20))
                        .withCentral(Duration.ofMinutes(5), Duration.ofSeconds(10), true));
        Cache4jSpringOptions options = options().cache(programmatic).declEnums(AutoDecls.class).build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TwoLevelCacheManager.create(options));
        assertTrue(e.getMessage().contains("namespace 冲突"), e.getMessage());
    }

    @Test
    @DisplayName("关闭自动创建时，未声明的 namespace 返回空")
    void undeclaredDisabledReturnsEmpty() {
        try (TwoLevelCacheManager manager = TwoLevelCacheManager.create(
                options().declEnums(AutoDecls.class).undeclaredEnabled(false).build())) {
            assertTrue(manager.cache("not-declared").isEmpty());
            assertTrue(manager.undeclaredNamespaces().isEmpty());
        }
    }

    private static Cache4jSpringOptions.Builder options() {
        return Cache4jSpringOptions.builder()
                .centralProvider(new InMemoryCentralCacheProvider(), true);
    }

    /**
     * 带 {@link CacheNamespace} 的声明枚举。
     */
    @CacheNamespace
    public enum AutoDecls implements CacheStrategyDecl {

        /**
         * 自动装配测试用。
         */
        AUTO_DECLARED("auto:declared", LocalStrategy.STATIC, "显式传入的声明");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        AutoDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }
}
