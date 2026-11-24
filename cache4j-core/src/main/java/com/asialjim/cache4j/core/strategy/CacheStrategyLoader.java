package com.asialjim.cache4j.core.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 {@code META-INF/services} 的<strong>文件约定</strong>发现缓存声明。
 *
 * <p>文件路径与内容格式完全沿用 {@code ServiceLoader}：路径是
 * {@code META-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl}，
 * 每行一个全限定类名，支持 {@code #} 注释与空行。但<strong>加载逻辑自研</strong>，原因：</p>
 *
 * <p>{@code ServiceLoader} 唯一的实例化方式是反射调用无参构造器，而
 * {@code java.lang.reflect.Constructor#newInstance} 对枚举类型直接抛
 * {@code IllegalArgumentException: Cannot reflectively create enum objects}。
 * 本库的声明必须是枚举，因此改用 {@code Class.forName} + {@link Class#getEnumConstants()}。</p>
 *
 * <p>多个 jar 里存在同名文件时，用 {@link ClassLoader#getResources} 读取<strong>全部</strong>并合并
 * （{@code getResource} 只返回第一个，会静默漏掉一半声明）。</p>
 *
 * @author Asial Jim
 */
public final class CacheStrategyLoader {

    /**
     * 声明文件路径（与 {@code ServiceLoader} 约定一致）。
     */
    public static final String DECL_RESOURCE =
            "META-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl";

    private static final Logger LOG = LoggerFactory.getLogger(CacheStrategyLoader.class);

    private final ClassLoader classLoader;

    /**
     * @param classLoader 类加载器；为 {@code null} 时用线程上下文类加载器
     */
    public CacheStrategyLoader(ClassLoader classLoader) {
        ClassLoader effective = classLoader != null
                ? classLoader : Thread.currentThread().getContextClassLoader();
        this.classLoader = effective != null ? effective : CacheStrategyLoader.class.getClassLoader();
    }

    /**
     * @return 使用线程上下文类加载器的实例
     */
    public static CacheStrategyLoader defaultLoader() {
        return new CacheStrategyLoader(null);
    }

    /**
     * 读取并翻译全部声明。
     *
     * @return 候选声明（保持文件顺序）
     */
    public List<CacheCandidate> load() {
        List<CacheCandidate> all = new ArrayList<>();
        CacheDeclTranslator translator = new CacheDeclTranslator();
        for (String className : declaredClassNames()) {
            Class<?> type = loadClass(className);
            all.addAll(translator.translate(type, "META-INF/services：" + DECL_RESOURCE));
        }
        if (all.isEmpty()) {
            LOG.debug("[{}]-{}-{}", "缓存装配", "声明", "未发现任何声明文件，" + DECL_RESOURCE + " 不存在或为空");
        }
        return all;
    }

    /**
     * 只读取文件里的类名（不加载类），供 AOT hints 与排障使用。
     *
     * @return 去重后的全限定类名（保持文件顺序）
     */
    public Set<String> declaredClassNames() {
        Set<String> names = new LinkedHashSet<>();
        Enumeration<URL> resources;
        try {
            resources = classLoader.getResources(DECL_RESOURCE);      // 全部，不是第一个
        } catch (IOException e) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "读取声明文件失败：" + DECL_RESOURCE, e);
        }
        while (resources.hasMoreElements()) {
            readLines(resources.nextElement(), names);
        }
        return names;
    }

    private void readLines(URL url, Set<String> names) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!names.add(trimmed)) {
                    LOG.debug("[{}]-{}-{}", "缓存装配", "声明", url + " 第 " + lineNo + " 行：" + trimmed + " 重复出现，已忽略");
                }
            }
        } catch (IOException e) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "读取声明文件失败：" + url, e);
        }
    }

    private Class<?> loadClass(String className) {
        try {
            // initialize=false：类初始化（枚举静态块）推迟到 getEnumConstants 时发生
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new CacheException(CacheResCode.INVALID_SPEC,
                    "声明文件里的类找不到：" + className + "（文件：" + DECL_RESOURCE + "）", e);
        }
    }
}
