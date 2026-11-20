package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.strategy.CacheCandidate;
import com.asialjim.cache4j.core.strategy.CacheDeclTranslator;
import com.asialjim.cache4j.core.strategy.BuiltinDefaults;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认的 {@link Cache4jSpringOptions}：从配置读 {@code cache4j.*}，不需要业务自己写 {@code @Bean}。
 *
 * <p>被 {@link Cache4jAutoScanConfiguration} 扫到即生效。业务想自己决定时，定义同名
 * {@code cache4jOptions} Bean 覆盖即可（纯 Spring 默认允许后定义覆盖前定义）。</p>
 *
 * <h2>为什么同时读 {@code @Value} 和 {@link Environment}</h2>
 *
 * <p>两种容器的配置来源不同，单靠任何一个都会漏：</p>
 * <ul>
 *   <li><strong>XML 容器</strong>：业务用 {@code PropertyPlaceholderConfigurer} /
 *       {@code <context:property-placeholder/>} 配属性，它们只喂给占位符解析器，<strong>不进
 *       {@link Environment}</strong> —— 只能靠 {@code @Value} 拿到；</li>
 *   <li><strong>注解容器</strong>：{@code AnnotationConfigApplicationContext} 默认不注册占位符解析器，
 *       {@code @Value} 会原样注入字面量 {@code "${...}"} —— 只能靠 {@link Environment} 拿到
 *       （系统属性 / 环境变量 / 手工加的 {@code MapPropertySource}）。</li>
 * </ul>
 *
 * <p>所以这里两个都读：注解值若是未解析的字面量就丢弃，回落到 {@code Environment}。</p>
 *
 * @author Asial Jim
 */
@Configuration(proxyBeanMethods = false)
public class Cache4jDefaultOptionsConfiguration {

    private static final String KEY_DECL_PACKAGES = "cache4j.decl.packages";

    private static final String KEY_STATS_ENABLED = "cache4j.stats.enabled";

    private static final String KEY_UNDECLARED_ENABLED = "cache4j.undeclared.enabled";

    /**
     * @param centralProvider   中央缓存 provider（由子模块或业务提供）
     * @param environment       注解容器里的配置源
     * @param declarations      容器里已有的 {@link CacheStrategyDecl}（业务用 component-scan 扫进来的）
     * @param declPackages      XML 容器里的声明扫描包
     * @param statsEnabled      XML 容器里的统计开关
     * @param undeclaredEnabled XML 容器里的未声明自动创建开关
     * @return 装配配置
     */
    @Bean
    public Cache4jSpringOptions cache4jOptions(
            CentralCacheProvider centralProvider,
            Environment environment,
            ObjectProvider<CacheNamespaceDeclarations> discovered,
            ObjectProvider<CacheStrategyDecl> declarations,
            @Value("${cache4j.decl.packages:}") String declPackages,
            @Value("${cache4j.stats.enabled:}") String statsEnabled,
            @Value("${cache4j.undeclared.enabled:}") String undeclaredEnabled) {
        return Cache4jSpringOptions.builder()
                .centralProvider(centralProvider, false)
                .caches(discover(discovered, declarations,
                        packages(value(declPackages, KEY_DECL_PACKAGES, environment))))
                .statsEnabled(flag(value(statsEnabled, KEY_STATS_ENABLED, environment), false))
                .undeclaredEnabled(flag(value(undeclaredEnabled, KEY_UNDECLARED_ENABLED, environment), true))
                .build();
    }

    /**
     * 汇总声明来源并<b>按 namespace 去重</b>：容器里扫到的与按包名扫到的可能是同一批枚举，
     * 重复交给装配层会被判成"不同来源争抢同一 namespace"。
     *
     * @param declarations 容器里已有的声明
     * @param packages     按包名扫描的范围
     * @return 去重后的缓存定义
     */
    private static List<CacheDefinition> discover(ObjectProvider<CacheNamespaceDeclarations> discovered,
                                                  ObjectProvider<CacheStrategyDecl> declarations,
                                                  List<String> packages) {
        CacheDeclTranslator translator = new CacheDeclTranslator();
        Map<String, CacheDefinition> merged = new LinkedHashMap<>();

        // 组件扫描命中并已被包装成载体的枚举
        for (CacheNamespaceDeclarations carrier : discovered) {
            for (CacheDefinition definition : carrier.definitions()) {
                merged.putIfAbsent(definition.namespace(), definition);
            }
        }
        // 业务自己以 @Bean 提供的单条声明（非枚举形态）
        for (CacheStrategyDecl declaration : declarations) {
            merged.putIfAbsent(declaration.getNamespace(),
                    BuiltinDefaults.definitionOf(declaration.getNamespace(), declaration.getStrategy()));
        }
        for (Class<?> type : new SpringCacheDeclScanner(ClassUtils.getDefaultClassLoader()).scan(packages)) {
            for (CacheCandidate candidate : translator.translate(type, "声明的包扫描")) {
                merged.putIfAbsent(candidate.namespace(), candidate.definition());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * @param annotated {@code @Value} 注入值（可能是未解析的字面量）
     * @param key       配置键
     * @param env       Environment
     * @return 有效值，没有则空串
     */
    private static String value(String annotated, String key, Environment env) {
        if (annotated != null && !annotated.isBlank() && !annotated.startsWith("${")) {
            return annotated.trim();
        }
        return env.getProperty(key, "");
    }

    private static List<String> packages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean flag(String raw, boolean fallback) {
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
    }
}
