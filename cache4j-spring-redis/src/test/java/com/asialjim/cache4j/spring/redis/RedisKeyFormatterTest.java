package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * key 布局测试（纯逻辑，不需要 Redis）。
 *
 * <p>规则：{@code _c4j:{namespace}:<key>}。{@code _c4j} 标识"由 cache4j 写入"，
 * {@code {namespace}} 是 cluster 的 hash tag，保证同 namespace 落同一 slot。</p>
 *
 * @author Asial Jim
 */
class RedisKeyFormatterTest {

    private final CacheKey key = new CacheKey("user:detail", "s2:42");

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("默认布局：_c4j:{namespace}:<key>")
    void defaultLayout() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        assertEquals("_c4j:{user:detail}:s2:42", text(keys.data(key)));
        assertEquals("_c4j:{user:detail}:__rev", text(keys.revision("user:detail")));
        assertEquals("_c4j:{user:detail}:__hot:w:17", text(keys.hotWindow("user:detail", 17L)));
        assertEquals("_c4j:{user:detail}:__hot:meta", text(keys.hotMeta("user:detail")));
        assertEquals("_c4j:{user:detail}:", text(keys.namespacePrefix("user:detail")));
        assertTrue(keys.hashTag());
    }

    @Test
    @DisplayName("所有 key 都以 _c4j 开头：一眼看出是 cache4j 写的")
    void allKeysCarryCache4jPrefix() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        for (byte[] raw : new byte[][]{
                keys.data(key), keys.revision("user:detail"), keys.hotWindow("user:detail", 1L),
                keys.hotMeta("user:detail"), keys.namespacePrefix("user:detail")}) {
            assertTrue(text(raw).startsWith(RedisKeyFormatter.DEFAULT_PREFIX),
                    () -> text(raw) + " 应以 _c4j 开头");
        }
    }

    @Test
    @DisplayName("hash tag 让同 namespace 的所有 key 结构一致（cluster 同 slot 的前提）")
    void hashTagGroupsByNamespace() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        String head = "_c4j:{user:detail}:";
        assertEquals(head + "s2:42", text(keys.data(key)));
        assertEquals(head + "__rev", text(keys.revision("user:detail")));
        assertTrue(text(keys.data(key)).startsWith(head));
        assertTrue(text(keys.revision("user:detail")).startsWith(head));
    }

    @Test
    @DisplayName("关掉 hash tag 后是 _c4j:namespace:key（集群下跨 key Lua 会 CROSSSLOT）")
    void hashTagCanBeDisabled() {
        RedisKeyFormatter keys = new RedisKeyFormatter("_c4j", false);
        assertEquals("_c4j:user:detail:s2:42", text(keys.data(key)));
        assertEquals("_c4j:user:detail:__rev", text(keys.revision("user:detail")));
        assertFalse(keys.hashTag());
    }

    @Test
    @DisplayName("版本 field 就是规范编码")
    void revisionField() {
        assertEquals("s2:42", text(RedisKeyFormatter.defaults().revisionField(key)));
    }
}
