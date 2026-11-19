package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.VersionedLookup;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 泛型下行 SPI：「存一个 {@code V}、给我版本」。
 *
 * <p>由<strong>真正与 Redis 发生 I/O 的适配模块</strong>实现（{@code cache4j-spring-redis}、未来的
 * {@code cache4j-spring-redisson} / {@code cache4j-solon-redis} …）。本接口刻意做到：</p>
 *
 * <ul>
 *   <li><strong>没有 {@code byte[]}</strong>：值就是 {@code V}，序列化在实现里；</li>
 *   <li><strong>没有 key 拼接</strong>：实现只收到 {@link CacheKey}（namespace + 规范编码），
 *       布局由实现决定；</li>
 *   <li><strong>没有 Lua / envelope 约束</strong>：怎么保证"值与版本原子"、值怎么封装，都是实现的事。</li>
 * </ul>
 *
 * <h2>实现者必须保证</h2>
 * <table border="1">
 *   <caption>契约性质</caption>
 *   <tr><th>性质</th><th>说明</th></tr>
 *   <tr><td>版本可用</td><td>只需保证"每次写产生不同的版本"；比较只用"不相等"，时间戳方案即可</td></tr>
 *   <tr><td>删除可观测</td><td>{@link #delete} 之后该 key 不能再出现在 {@link #revisions} 里</td></tr>
 *   <tr><td>原子性</td><td>值写入与版本更新必须原子（Redis 用 Lua，其它用事务/脚本）</td></tr>
 *   <tr><td>过期语义</td><td>数据按 TTL 过期后 {@link #read} 视为 miss、{@link #revisions} 视为缺失；
 *       版本元数据的死条目清理也由实现负责</td></tr>
 *   <tr><td>线程安全</td><td>可被多个缓存视图并发调用</td></tr>
 *   <tr><td>故障表达</td><td>统一抛 {@link RedisAccessException}；序列化失败直接抛
 *       {@code CacheException(CODEC_*)}</td></tr>
 * </table>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface RedisValueStore<V> extends AutoCloseable {

    /**
     * 读：版本 + 三态。key 不存在或已过期时返回 {@code revision = 0} 且 {@code Miss}。
     *
     * @param key key
     * @return 版本 + 三态
     */
    VersionedLookup<V> read(CacheKey key);

    /**
     * 批量读。
     *
     * @param keys key 集合
     * @return key → 版本 + 三态
     */
    Map<CacheKey, VersionedLookup<V>> readAll(Collection<CacheKey> keys);

    /**
     * 写值：与版本更新<strong>原子</strong>完成。
     *
     * @param key   key
     * @param value 值
     * @param ttl   存活时间；{@code null} 表示永不过期（仅上层显式允许时）
     * @return 该 key 的新版本
     */
    long write(CacheKey key, V value, Duration ttl);

    /**
     * 写"已确认不存在"。
     *
     * @param key key
     * @param ttl 存活时间
     * @return 该 key 的新版本
     */
    long writeNegative(CacheKey key, Duration ttl);

    /**
     * 原子"不存在才写"。
     *
     * @param key   key
     * @param value 值
     * @param ttl   存活时间
     * @return 写入成功返回新版本；已存在返回 {@code empty} 且<strong>不</strong>更新版本
     */
    OptionalLong writeIfAbsent(CacheKey key, V value, Duration ttl);

    /**
     * 删除数据并删掉该 key 的版本（删除对其他节点可观测）。
     *
     * @param key key
     */
    void delete(CacheKey key);

    /**
     * 清空该命名空间（含版本元数据）。
     */
    void clear();

    /**
     * 批量取版本（对账用）：只取版本、不取数据值；缺失表示已失效。
     *
     * @param keys key 集合
     * @return key → 版本
     */
    Map<CacheKey, Long> revisions(Collection<CacheKey> keys);

    /**
     * 指定 key 的当前版本；缺失返回 {@code 0}。
     *
     * @param key key
     * @return 版本
     */
    long revision(CacheKey key);

    /**
     * 热 key 上报 + 状态查询，一次往返完成。
     *
     * @param namespace 缓存名
     * @param key       key
     * @param delta     增量；{@code 0} 表示纯查询
     * @return 报告
     */
    HotKeyReport reportHotKey(String namespace, CacheKey key, long delta);

    /**
     * 批量热 key 上报 + 状态查询。
     *
     * @param namespace 缓存名
     * @param deltas    key → 增量
     * @return key → 报告
     */
    Map<CacheKey, HotKeyReport> reportHotKeyAll(String namespace, Map<CacheKey, Long> deltas);

    /**
     * 轻量探活；失败返回 {@code false}，不抛异常。
     *
     * @return 是否可用
     */
    boolean ping();

    /**
     * 释放连接等资源；必须幂等。
     */
    @Override
    void close();
}
