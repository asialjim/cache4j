package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.CacheStrategy;

import com.asialjim.cache4j.central.InMemoryCentralCacheProvider;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.Cache4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Cache 桥接与管理器测试（用内存中央实现，不需要 Redis / Boot）。
 *
 * @author Asial Jim
 */
class SpringCacheAdapterTest {

    private static final String NS = "user:detail";

    private TwoLevelCacheManager manager;
    private SpringCacheManagerAdapter cacheManager;
    private SpringCacheAdapter cache;

    @BeforeEach
    void setUp() {
        Cache4jSpringOptions options = Cache4jSpringOptions.builder()
                .centralProvider(new InMemoryCentralCacheProvider(), true)
                .cache(CacheDefinition.of(
                        CacheStrategy.staticCache(NS, 100, Duration.ofMinutes(1))
                                .withCentral(Duration.ofMinutes(10), Duration.ZERO, true)))
                .build();
        manager = TwoLevelCacheManager.create(options);
        cacheManager = new SpringCacheManagerAdapter(manager);
        cache = (SpringCacheAdapter) cacheManager.getCache(NS);
        assertNotNull(cache);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    @DisplayName("管理器：声明的缓存名集合")
    void managerExposesNames() {
        assertEquals(java.util.List.of(NS), java.util.List.copyOf(cacheManager.getCacheNames()));
        Optional<Cache4j<Object, Object>> found = manager.cache(NS);
        assertTrue(found.isPresent());
    }

    @Test
    @DisplayName("未声明的 namespace：按内建默认自动创建、告警并记录（不报错）")
    void undeclaredNamespaceIsCreatedOnDemand() {
        // 启动期无法穷举业务会用哪些缓存名，所以"能跑 + 必须可见"
        org.springframework.cache.Cache created = cacheManager.getCache("not-exists");
        assertNotNull(created, "未声明的 namespace 应自动创建");
        assertTrue(manager.undeclaredNamespaces().contains("not-exists"),
                "未声明的 namespace 必须被记录（告警与运维视图用）");
        // 第二次读到的是同一个底层缓存（Spring 包装器每次新建，但底层实例复用）
        Cache4j<Object, Object> first = manager.cache("not-exists").orElseThrow();
        Cache4j<Object, Object> second = manager.cache("not-exists").orElseThrow();
        assertSame(first, second, "未声明的 namespace 只应装配一次");
        assertNotNull(created);
    }

    @Test
    @DisplayName("put / get / evict / clear 语义")
    void basicSemantics() {
        cache.put("42", "u42");
        assertEquals("u42", cache.get("42").get());
        assertEquals("u42", cache.get("42", String.class));
        assertEquals(cache.unwrap(), cache.getNativeCache());

        cache.evict("42");
        assertNull(cache.get("42"));

        cache.put("a", "va");
        cache.clear();
        assertNull(cache.get("a"));
    }

    @Test
    @DisplayName("类型不匹配抛 IllegalStateException（与 Spring 语义一致）")
    void typeMismatch() {
        cache.put("42", "u42");
        assertThrows(IllegalStateException.class, () -> cache.get("42", Integer.class));
    }

    @Test
    @DisplayName("get(key, Callable)：未命中回源，命中不调用 Callable")
    void getWithCallable() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> loader = () -> {
            calls.incrementAndGet();
            return "loaded";
        };

        assertEquals("loaded", cache.get("42", loader));
        assertEquals("loaded", cache.get("42", loader));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("get(key, Callable) 的 loader 异常转成 ValueRetrievalException")
    void callableFailure() {
        Callable<String> failing = () -> {
            throw new java.io.IOException("boom");
        };
        assertThrows(org.springframework.cache.Cache.ValueRetrievalException.class,
                () -> cache.get("42", failing));
    }

    @Test
    @DisplayName("putIfAbsent：写入成功返回 null，已存在返回现有值")
    void putIfAbsentSemantics() {
        assertNull(cache.putIfAbsent("42", "first"));
        assertEquals("first", cache.putIfAbsent("42", "second").get());
        assertEquals("first", cache.get("42").get());
    }

    @Test
    @DisplayName("管理器关闭幂等")
    void closeIsIdempotent() {
        manager.close();
        manager.close();
    }
}
