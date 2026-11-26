package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.local.LocalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code META-INF/services} 文件约定的加载：读全部同名资源、跳过注释/空行、去重、类找不到时明确报错。
 *
 * @author Asial Jim
 */
class CacheStrategyLoaderTest {

    @Test
    @DisplayName("读取 test resources 下的声明文件，翻译出全部候选")
    void loadsDeclarationsFromServiceFile() {
        List<CacheCandidate> candidates = CacheStrategyLoader.defaultLoader().load();

        assertEquals(2, candidates.size(), "重复的类名应被去重：" + candidates);
        assertTrue(candidates.stream().anyMatch(c -> "user:detail".equals(c.namespace())));
        assertTrue(candidates.stream().anyMatch(c -> "order:snapshot".equals(c.namespace())));
        assertTrue(candidates.stream().allMatch(c -> c.origin().contains("META-INF/services")));
        assertTrue(candidates.stream().anyMatch(
                c -> c.definition().strategy().localStrategy() == LocalStrategy.NONE));
    }

    @Test
    @DisplayName("declaredClassNames 只读类名、不加载类")
    void declaredClassNamesDoesNotLoadClasses() {
        Set<String> names = CacheStrategyLoader.defaultLoader().declaredClassNames();

        assertEquals(1, names.size(), "重复行应去重：" + names);
        assertTrue(names.contains(LoaderFixtureDecls.class.getName()));
    }

    @Test
    @DisplayName("类找不到：报错并指出文件名")
    void reportsMissingClass(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(CacheStrategyLoader.DECL_RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# 一行指向不存在的类\ncom.acme.DoesNotExist\n");

        URL[] urls = {tempDir.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            CacheException e = assertThrows(CacheException.class,
                    () -> new CacheStrategyLoader(loader).load());
            assertTrue(e.getMessage().contains("com.acme.DoesNotExist"), e.getMessage());
            assertTrue(e.getMessage().contains("声明文件里的类找不到"), e.getMessage());
        }
    }

    @Test
    @DisplayName("文件里是普通类而不是枚举：报错")
    void rejectsNonEnumInFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(CacheStrategyLoader.DECL_RESOURCE);
        Files.createDirectories(file.getParent());
        // String 不是 CacheStrategyDecl，先命中"未实现"
        Files.writeString(file, "java.lang.String\n");

        URL[] urls = {tempDir.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            CacheException e = assertThrows(CacheException.class,
                    () -> new CacheStrategyLoader(loader).load());
            assertTrue(e.getMessage().contains("未实现 CacheStrategyDecl"), e.getMessage());
        }
    }

    @Test
    @DisplayName("没有声明文件：返回空列表，不报错")
    void emptyWhenNoServiceFile(@TempDir Path tempDir) throws IOException {
        URL[] urls = {tempDir.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            assertTrue(new CacheStrategyLoader(loader).load().isEmpty());
            assertTrue(new CacheStrategyLoader(loader).declaredClassNames().isEmpty());
        }
    }
}
