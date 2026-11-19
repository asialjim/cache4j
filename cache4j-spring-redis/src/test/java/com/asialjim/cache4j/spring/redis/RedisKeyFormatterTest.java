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
 * @author Asial Jim
 */
class RedisKeyFormatterTest {

    private final CacheKey key = new CacheKey("user:detail", "s2:42");

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("默认 hash tag 布局")
    void hashTagLayout() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        assertEquals("cache4j:{user:detail}:s2:42", text(keys.data(key)));
        assertEquals("cache4j:{user:detail}:__rev", text(keys.revision("user:detail")));
        assertEquals("cache4j:{user:detail}:__hot:w:17", text(keys.hotWindow("user:detail", 17L)));
        assertEquals("cache4j:{user:detail}:__hot:meta", text(keys.hotMeta("user:detail")));
        assertEquals("cache4j:{user:detail}:", text(keys.namespacePrefix("user:detail")));
        assertTrue(keys.hashTag());
    }

    @Test
    @DisplayName("关闭 hash tag 时用长度前缀，避免清理误伤兄弟命名空间")
    void lengthPrefixLayout() {
        RedisKeyFormatter keys = new RedisKeyFormatter("prod:cache4j", false);
        assertEquals("prod:cache4j:11:user:detail:s2:42", text(keys.data(key)));
        assertEquals("prod:cache4j:11:user:detail:__rev", text(keys.revision("user:detail")));
        assertFalse(keys.hashTag());

        // "user" 的清理前缀不可能是 "user:detail" 的前缀
        String userPrefix = text(keys.namespacePrefix("user"));
        String detailKey = text(keys.data(key));
        assertFalse(detailKey.startsWith(userPrefix),
                () -> detailKey + " 不应以 " + userPrefix + " 开头");
    }

    @Test
    @DisplayName("hash tag 形式下兄弟命名空间同样不互相误伤")
    void hashTagDoesNotLeakSiblings() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        String userPrefix = text(keys.namespacePrefix("user"));           // cache4j:{user}:
        String detailKey = text(keys.data(new CacheKey("user:detail", "s2:42")));
        assertFalse(detailKey.startsWith(userPrefix));
    }

    @Test
    @DisplayName("版本 field 就是规范编码")
    void revisionField() {
        RedisKeyFormatter keys = RedisKeyFormatter.defaults();
        assertEquals("s2:42", text(keys.revisionField(key)));
    }
}
