package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * key 布局测试（纯逻辑，不需要 Redis）。
 *
 * <p>核心规则：<strong>所有存储 key 都以用户自定义的 namespace 开头</strong>，
 * 没有部署前缀、没有 {@code {hash tag}}、没有长度前缀。</p>
 *
 * @author Asial Jim
 */
class RedisKeyFormatterTest {

    private final CacheKey key = new CacheKey("user:detail", "s2:42");

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("数据 key 就是 namespace:编码，一个字节都不多")
    void dataKeyStartsWithNamespace() {
        assertEquals("user:detail:s2:42", text(RedisKeyFormatter.defaults().data(key)));
    }

    @Test
    @DisplayName("版本表 / 热窗口 / 热元信息 / 清理前缀都以 namespace 开头")
    void allKeysStartWithNamespace() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        String namespace = "user:detail";

        assertEquals("user:detail:__rev", text(keys.revision(namespace)));
        assertEquals("user:detail:__hot:w:17", text(keys.hotWindow(namespace, 17L)));
        assertEquals("user:detail:__hot:meta", text(keys.hotMeta(namespace)));
        assertEquals("user:detail:", text(keys.namespacePrefix(namespace)));

        for (byte[] raw : new byte[][]{
                keys.data(key), keys.revision(namespace), keys.hotWindow(namespace, 17L),
                keys.hotMeta(namespace), keys.namespacePrefix(namespace)}) {
            assertTrue(text(raw).startsWith(namespace), () -> text(raw) + " 应以 " + namespace + " 开头");
        }
    }

    @Test
    @DisplayName("没有任何形式的全局前缀 / hash tag / 长度前缀")
    void noPrefixNoHashTagNoLengthPrefix() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        String data = text(keys.data(key));

        assertEquals('u', data.charAt(0), "第一个字符就是 namespace 的首字符");
        assertEquals(-1, data.indexOf('{'), "不能用 {hash tag}");
        assertEquals(-1, data.indexOf('}'), "不能用 {hash tag}");
        // 长度前缀形如 "11:user:detail"：namespace 之前不该出现任何 ":"
        assertEquals(0, data.indexOf("user:detail"), "namespace 必须从第 0 位开始");
    }

    @Test
    @DisplayName("版本 field 就是规范编码")
    void revisionField() {
        assertEquals("s2:42", text(RedisKeyFormatter.defaults().revisionField(key)));
    }
}
