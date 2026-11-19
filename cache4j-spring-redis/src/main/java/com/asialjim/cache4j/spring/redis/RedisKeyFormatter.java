package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheKey;

import java.nio.charset.StandardCharsets;

/**
 * key 布局（<strong>适配层职责</strong>）。
 *
 * <p>布局与 {@code CENTRAL §3} 的参考约定一致：</p>
 *
 * <pre>{@code
 * 数据：   cache4j:{user:detail}:s2:42
 * 版本表： cache4j:{user:detail}:__rev          HASH   field=s2:42 -> "<revision>|<expireAtMillis>"
 * 热窗口： cache4j:{user:detail}:__hot:w:<id>   ZSET
 * 热元信息：cache4j:{user:detail}:__hot:meta    HASH
 * }</pre>
 *
 * <p>默认开启 hash tag（{@code {namespace}}），让"版本 + 数据"落同一 slot，MGET / 多 key Lua 可用；
 * 关闭 hash tag 时 namespace 用<strong>长度前缀</strong>（{@code 11:user:detail}），避免清理
 * {@code user} 时误伤 {@code user:detail}。</p>
 *
 * @author Asial Jim
 */
public final class RedisKeyFormatter {

    private static final byte[] REVISION_SUFFIX = "__rev".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOT_PREFIX = "__hot:w:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOT_META_SUFFIX = "__hot:meta".getBytes(StandardCharsets.UTF_8);
    private static final byte COLON = ':';

    private final byte[] globalPrefix;
    private final boolean hashTag;

    /**
     * @param globalPrefix 部署级前缀，例如 {@code cache4j} 或 {@code prod:cache4j}
     * @param hashTag      是否用 {@code {namespace}} 做 hash tag
     */
    public RedisKeyFormatter(String globalPrefix, boolean hashTag) {
        if (globalPrefix == null || globalPrefix.isBlank()) {
            throw new IllegalArgumentException("globalPrefix 不能为空");
        }
        this.globalPrefix = globalPrefix.getBytes(StandardCharsets.UTF_8);
        this.hashTag = hashTag;
    }

    /**
     * 默认布局：前缀 {@code cache4j}，开启 hash tag。
     *
     * @return formatter
     */
    public static RedisKeyFormatter defaults() {
        return new RedisKeyFormatter("cache4j", true);
    }

    /**
     * @return 是否启用 hash tag
     */
    public boolean hashTag() {
        return hashTag;
    }

    /**
     * 数据 key。
     *
     * @param key 逻辑 key
     * @return 存储 key
     */
    public byte[] data(CacheKey key) {
        byte[] namespacePart = namespacePart(key.namespace());
        byte[] encoded = key.encoded().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[globalPrefix.length + 1 + namespacePart.length + 1 + encoded.length];
        int index = 0;
        index = copy(globalPrefix, result, index);
        result[index++] = COLON;
        index = copy(namespacePart, result, index);
        result[index++] = COLON;
        copy(encoded, result, index);
        return result;
    }

    /**
     * 版本表 key（每个命名空间一张 HASH）。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] revision(String namespace) {
        return concat(namespaceHead(namespace), REVISION_SUFFIX);
    }

    /**
     * 热 key 窗口 key。
     *
     * @param namespace 缓存名
     * @param windowId  窗口号
     * @return 存储 key
     */
    public byte[] hotWindow(String namespace, long windowId) {
        byte[] suffix = (new String(HOT_PREFIX, StandardCharsets.UTF_8) + windowId)
                .getBytes(StandardCharsets.UTF_8);
        return concat(namespaceHead(namespace), suffix);
    }

    /**
     * 热 key 元信息 key。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] hotMeta(String namespace) {
        return concat(namespaceHead(namespace), HOT_META_SUFFIX);
    }

    /**
     * 该命名空间下所有 key 的前缀（以 {@code :} 结尾），用于 SCAN 清理。
     *
     * @param namespace 缓存名
     * @return 前缀
     */
    public byte[] namespacePrefix(String namespace) {
        return concat(namespaceHead(namespace), new byte[0]);
    }

    /**
     * 版本 field：直接用规范编码（与数据 key 的后半段一致）。
     *
     * @param key 逻辑 key
     * @return field 字节
     */
    public byte[] revisionField(CacheKey key) {
        return key.encoded().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] namespaceHead(String namespace) {
        byte[] namespacePart = namespacePart(namespace);
        byte[] result = new byte[globalPrefix.length + 1 + namespacePart.length + 1];
        int index = 0;
        index = copy(globalPrefix, result, index);
        result[index++] = COLON;
        index = copy(namespacePart, result, index);
        result[index] = COLON;
        return result;
    }

    private byte[] namespacePart(String namespace) {
        if (hashTag) {
            byte[] raw = namespace.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[raw.length + 2];
            result[0] = '{';
            System.arraycopy(raw, 0, result, 1, raw.length);
            result[result.length - 1] = '}';
            return result;
        }
        byte[] raw = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = (raw.length + ":").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(raw, 0, result, prefix.length, raw.length);
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static int copy(byte[] source, byte[] target, int offset) {
        System.arraycopy(source, 0, target, offset, source.length);
        return offset + source.length;
    }
}
