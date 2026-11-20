package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheKey;

import java.nio.charset.StandardCharsets;

/**
 * key 布局（<strong>适配层职责</strong>）。
 *
 * <h2>规则：{@code _c4j:{namespace}:<key>}</h2>
 *
 * <pre>{@code
 * 数据：    _c4j:{user:detail}:s2:42
 * 版本表：  _c4j:{user:detail}:__rev           HASH  field=s2:42 -> "<revision>|<expireAtMillis>"
 * 热窗口：  _c4j:{user:detail}:__hot:w:<id>    ZSET
 * 热元信息：_c4j:{user:detail}:__hot:meta      HASH
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code _c4j}</strong>：cache4j 的简写。凡是以 {@code _c4j} 开头的 key，都是本库写入的
 *       —— 运维排查时一眼能分清"这是缓存"还是业务自己的数据；</li>
 *   <li><strong>{@code {namespace}}</strong>：Redis Cluster 的 hash tag。同一个 namespace 的数据、版本表、
 *       热 key 窗口因此落在<strong>同一个 slot</strong>，跨 key 的 Lua（读版本 + 读数据、写数据 + 写版本、
 *       删除数据 + 删版本）在集群模式下才能正常执行；</li>
 *   <li>花括号外只剩 {@code <key>} 的规范编码，不再有长度前缀。</li>
 * </ul>
 *
 * <p>{@link #RedisKeyFormatter(String, boolean)} 可以关掉 hash tag（key 变成
 * {@code _c4j:namespace:key}）——<strong>但集群模式下跨 key 的 Lua 会直接报 {@code CROSSSLOT}，
 * 只有单机/哨兵能这么用</strong>。</p>
 *
 * @author Asial Jim
 */
public final class RedisKeyFormatter {

    /**
     * 默认前缀：cache4j 的简写。
     */
    public static final String DEFAULT_PREFIX = "_c4j";

    private static final byte[] REVISION_SUFFIX = "__rev".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HOT_PREFIX = "__hot:w:".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HOT_META_SUFFIX = "__hot:meta".getBytes(StandardCharsets.UTF_8);

    private static final byte COLON = ':';

    private final byte[] globalPrefix;

    private final boolean hashTag;

    /**
     * 默认布局：前缀 {@code _c4j}，开启 hash tag。
     */
    public RedisKeyFormatter() {
        this(DEFAULT_PREFIX, true);
    }

    /**
     * @param globalPrefix 部署级前缀，默认 {@code _c4j}（标识"由 cache4j 写入"）
     * @param hashTag      是否用 {@code {namespace}} 做 hash tag（集群模式必须开启）
     */
    public RedisKeyFormatter(String globalPrefix, boolean hashTag) {
        if (globalPrefix == null || globalPrefix.isBlank()) {
            throw new IllegalArgumentException("globalPrefix 不能为空");
        }
        this.globalPrefix = globalPrefix.getBytes(StandardCharsets.UTF_8);
        this.hashTag = hashTag;
    }

    /**
     * 默认布局。
     *
     * @return formatter
     */
    public static RedisKeyFormatter defaults() {
        return new RedisKeyFormatter();
    }

    /**
     * @return 是否启用 hash tag
     */
    public boolean hashTag() {
        return hashTag;
    }

    /**
     * 数据 key：{@code _c4j:{namespace}:<encodedKey>}。
     *
     * @param key 逻辑 key
     * @return 存储 key
     */
    public byte[] data(CacheKey key) {
        byte[] head = namespaceHead(key.namespace());
        byte[] encoded = key.encoded().getBytes(StandardCharsets.UTF_8);
        return concat(head, encoded);
    }

    /**
     * 版本表 key（每个命名空间一张 HASH）：{@code _c4j:{namespace}:__rev}。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] revision(String namespace) {
        return concat(namespaceHead(namespace), REVISION_SUFFIX);
    }

    /**
     * 热 key 窗口 key：{@code _c4j:{namespace}:__hot:w:<id>}。
     *
     * @param namespace 缓存名
     * @param windowId  窗口号
     * @return 存储 key
     */
    public byte[] hotWindow(String namespace, long windowId) {
        return concat(namespaceHead(namespace), (new String(HOT_PREFIX, StandardCharsets.UTF_8) + windowId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 热 key 元信息 key：{@code _c4j:{namespace}:__hot:meta}。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] hotMeta(String namespace) {
        return concat(namespaceHead(namespace), HOT_META_SUFFIX);
    }

    /**
     * 该命名空间下所有 key 的前缀，用于 SCAN 清理。
     *
     * @param namespace 缓存名
     * @return 前缀
     */
    public byte[] namespacePrefix(String namespace) {
        return namespaceHead(namespace);
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

    /**
     * @param namespace 缓存名
     * @return {@code _c4j:{namespace}:}
     */
    private byte[] namespaceHead(String namespace) {
        byte[] namespacePart = namespacePart(namespace);
        byte[] result = new byte[globalPrefix.length + 1 + namespacePart.length + 1];
        int index = 0;
        System.arraycopy(globalPrefix, 0, result, index, globalPrefix.length);
        index += globalPrefix.length;
        result[index++] = COLON;
        System.arraycopy(namespacePart, 0, result, index, namespacePart.length);
        index += namespacePart.length;
        result[index] = COLON;
        return result;
    }

    private byte[] namespacePart(String namespace) {
        byte[] raw = namespace.getBytes(StandardCharsets.UTF_8);
        if (!hashTag) {
            return raw;
        }
        byte[] result = new byte[raw.length + 2];
        result[0] = '{';
        System.arraycopy(raw, 0, result, 1, raw.length);
        result[result.length - 1] = '}';
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
