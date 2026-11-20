package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.CacheKey;

import java.nio.charset.StandardCharsets;

/**
 * key 布局（<strong>适配层职责</strong>）。
 *
 * <h2>规则：key 与 namespace 一一对应，不加任何前缀</h2>
 *
 * <pre>{@code
 * 数据：   <namespace>:<encodedKey>              user:detail:s2:42
 * 版本表： <namespace>:__rev                     HASH  field=s2:42 -> "<revision>|<expireAtMillis>"
 * 热窗口： <namespace>:__hot:w:<id>              ZSET
 * 热元信息：<namespace>:__hot:meta                HASH
 * }</pre>
 *
 * <p>存储 key <strong>总是以用户自定义的 namespace 开头</strong>，没有部署前缀、没有
 * {@code {hash tag}}、没有长度前缀。运维在 redis-cli 里看到的 {@code user:detail:...}
 * 就是业务代码里写的那个 namespace，一眼能对上。</p>
 *
 * <h2>两个刻意的取舍</h2>
 *
 * <ul>
 *   <li><strong>不用 hash tag</strong>：{@code {namespace}} 会让 key 以 {@code {} 开头，
 *       违反"以 namespace 开头"。代价是 Redis <strong>Cluster 下同一 namespace 的 key 不在同一 slot</strong>，
 *       {@code MGET} / 多 key Lua 会跨 slot 而失败——单机与哨兵没有这个限制。
 *       Cluster 部署需要按 namespace 做 client-side 路由，或接受逐 key 读取；</li>
 *   <li><strong>namespace 不加长度前缀</strong>：因此 {@code a} 与 {@code a:b} 两个 namespace 并存时，
 *       前者的清理前缀 {@code a:} 会覆盖后者。namespace 是显式声明的，这种重叠属于配置错误，
 *       提交前用声明冲突检测即可发现——不值得为它牺牲 key 的可读性。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public final class RedisKeyFormatter {

    private static final byte[] REVISION_SUFFIX = "__rev".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HOT_PREFIX = "__hot:w:".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HOT_META_SUFFIX = "__hot:meta".getBytes(StandardCharsets.UTF_8);

    private static final byte COLON = ':';

    /**
     * 无状态，可任意创建。
     */
    public RedisKeyFormatter() {
    }

    /**
     * 默认布局（也是唯一布局）。
     *
     * @return formatter
     */
    public static RedisKeyFormatter defaults() {
        return new RedisKeyFormatter();
    }

    /**
     * 数据 key：{@code <namespace>:<encodedKey>}。
     *
     * @param key 逻辑 key
     * @return 存储 key
     */
    public byte[] data(CacheKey key) {
        return (key.namespace() + ":" + key.encoded()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 版本表 key（每个命名空间一张 HASH）：{@code <namespace>:__rev}。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] revision(String namespace) {
        return concat(namespaceHead(namespace), REVISION_SUFFIX);
    }

    /**
     * 热 key 窗口 key：{@code <namespace>:__hot:w:<id>}。
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
     * 热 key 元信息 key：{@code <namespace>:__hot:meta}。
     *
     * @param namespace 缓存名
     * @return 存储 key
     */
    public byte[] hotMeta(String namespace) {
        return concat(namespaceHead(namespace), HOT_META_SUFFIX);
    }

    /**
     * 该命名空间下所有 key 的前缀（{@code <namespace>:}），用于 SCAN 清理。
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
     * @return {@code <namespace>:}
     */
    private byte[] namespaceHead(String namespace) {
        byte[] raw = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[raw.length + 1];
        System.arraycopy(raw, 0, result, 0, raw.length);
        result[raw.length] = COLON;
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
