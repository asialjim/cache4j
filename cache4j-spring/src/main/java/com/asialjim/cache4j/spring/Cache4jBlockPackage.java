package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.strategy.CacheBlock;
import com.asialjim.cache4j.strategy.CacheBlockTranslator;
import com.asialjim.cache4j.strategy.CacheStrategyProperty;
import com.asialjim.cache4j.strategy.CacheStrategyReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 扫描并注册：把 {@link CacheBlock} 枚举里的声明翻译成策略，注册进 {@link CacheStrategyReader}。
 *
 * <p>本类由 {@link Cache4jBlockRegister} 注册成 bean，并通过 {@code init} 在
 * <strong>bean 初始化阶段</strong>执行——这个时机很关键：此时容器里的
 * {@code CacheStrategyReader} 已经就位，而业务侧还没有任何人查过策略，
 * 不会出现"先缓存了未声明、后注册就再也拿不到"的问题。</p>
 *
 * @author Asial Jim
 */
public class Cache4jBlockPackage {

    private static final Logger log = LoggerFactory.getLogger(Cache4jBlockPackage.class);

    private static final CacheBlockTranslator TRANSLATOR = CacheBlockTranslator.INSTANCE;

    /**
     * 待扫描的包。
     */
    private final Set<String> packages;

    /**
     * 策略读取器；扫描结果通过它注册。
     */
    private final CacheStrategyReader cacheStrategyReader;

    public Cache4jBlockPackage(Set<String> packages, CacheStrategyReader cacheStrategyReader) {
        this.packages = packages == null ? Set.of() : new LinkedHashSet<>(packages);
        this.cacheStrategyReader = cacheStrategyReader;
    }

    /**
     * 由 Spring 按 {@code init-method} 调用：扫描并注册。
     *
     * <p>单个声明翻译失败<strong>不中断整体注册</strong>——把能注册的注册上，
     * 失败的那条记 warn。启动期因为一条声明写错而整个应用起不来，代价太大。</p>
     */
    @SuppressWarnings("unused")
    public void init() {
        if (packages.isEmpty()) {
            return;
        }
        List<CacheStrategyProperty> decls = cacheStrategyDecls();
        int registered = 0;
        for (CacheStrategyProperty property : decls) {
            try {
                cacheStrategyReader.registerIfAbsent(property);
                registered++;
            } catch (RuntimeException e) {
                log.warn("[{}]-{}-{}", "缓存块声明注册", "注册声明", "namespace=" + property.getNamespace() + " 注册失败：" + e);
            }
        }
        log.debug("[{}]-{}-{}", "缓存块声明注册", "扫描完成", "packages=" + packages + " 扫到类型=" + decls.size() + " 条声明");
    }

    private List<CacheStrategyProperty> cacheStrategyDecls() {
        List<CacheStrategyProperty> list = new ArrayList<>();
        List<Class<? extends CacheBlock>> scanned = CacheBlockScanner.scan(packages);
        for (Class<? extends CacheBlock> type : scanned) {
            if (!type.isEnum()) {
                // 扫描器已过滤，这里再挡一层：本体系只认枚举，普通类无法枚举出完整声明
                continue;
            }
            CacheBlock[] constants = type.getEnumConstants();
            if (constants == null) {
                continue;
            }
            for (CacheBlock block : constants) {
                if (Objects.isNull(block)) {
                    continue;
                }
                CacheStrategyProperty property = TRANSLATOR.translate(block);
                if (property != null) {
                    list.add(property);
                }
            }
        }
        return list;
    }
}
