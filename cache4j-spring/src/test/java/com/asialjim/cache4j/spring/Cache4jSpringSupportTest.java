package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * key 生成器与错误处理器测试。
 *
 * @author Asial Jim
 */
class Cache4jSpringSupportTest {

    private final KeyGenerator keyGenerator = new Cache4jKeyGenerator();

    @Test
    @DisplayName("key 生成器：多参数编码为规范数组")
    void generateFromParams() throws Exception {
        Method method = Sample.class.getDeclaredMethod("find", String.class, int.class);
        Object key = keyGenerator.generate(new Sample(), method, "a", 1);
        assertEquals("a2[s1:a,i1:1]", key);
    }

    @Test
    @DisplayName("无参方法用方法标识兜底，避免互相覆盖")
    void generateWithoutParams() throws Exception {
        Method method = Sample.class.getDeclaredMethod("all");
        Object first = keyGenerator.generate(new Sample(), method);
        Object second = keyGenerator.generate(new Other(), method);
        assertEquals(false, first.equals(second));
    }

    @Test
    @DisplayName("failOpen=true：基础设施异常被吞掉，业务继续")
    void failOpenSwallows() {
        CacheErrorHandler handler = new Cache4jCacheErrorHandler(true);
        handler.handleCacheGetError(new IllegalStateException("redis down"), null, "k");
    }

    @Test
    @DisplayName("failOpen=false：异常原样抛出")
    void failClosedPropagates() {
        CacheErrorHandler handler = new Cache4jCacheErrorHandler(false);
        assertThrows(IllegalStateException.class,
                () -> handler.handleCacheGetError(new IllegalStateException("redis down"), null, "k"));
    }

    @Test
    @DisplayName("编解码错误永不吞（即使 failOpen=true）")
    void codecErrorsAlwaysThrow() {
        CacheErrorHandler handler = new Cache4jCacheErrorHandler(true);
        CacheException codec = new CacheException(CacheResCode.CODEC_DECODE_FAILED, "bad data");
        assertThrows(CacheException.class,
                () -> handler.handleCacheGetError(codec, null, "k"));
    }

    /**
     * 反射用样例。
     */
    static class Sample {
        void find(String id, int page) {
            // 采样方法
        }

        void all() {
            // 无参
        }
    }

    /**
     * 另一个样例类型（验证无参方法 key 不冲突）。
     */
    static class Other {
        void all() {
            // 无参
        }
    }

    /**
     * 占位，避免未使用的 Cache 导入告警。
     */
    @SuppressWarnings("unused")
    private static void touch(Cache cache) {
        // 仅为类型引用
    }
}
