package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 中央缓存 SPI。
 *
 * <p>中央缓存是缓存数据的<strong>权威副本</strong>，并负责维护每个 key 的版本号。关键约定：</p>
 * <ul>
 *   <li>{@link Cache#put} / {@link Cache#putIfAbsent} / {@link Cache#evict} / {@link Cache#clear}
 *       <strong>必须</strong>复用带版本的方法并在内部丢弃返回值 —— 不存在"只写值、不更新版本"
 *       的中央写路径；</li>
 *   <li>版本更新必须与写操作<strong>原子</strong>完成；</li>
 *   <li>实现<strong>不吞异常</strong>：连接失败、命令失败、超时都抛
 *       {@link com.asialjim.cache4j.core.CacheException}；{@code failOpen} 是编排层的策略；</li>
 *   <li>{@link #evict} 会把该 key 的版本一并删掉：删除对其他节点可观测（对账时"中央已无此 key"）；</li>
 *   <li>本 SPI 不认识 Redis，也不认识任何客户端。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface CentralCache<V> extends Cache4j<CacheKey, V> {

    /**
     * 批量读；返回顺序与入参无关（按 key 索引）。
     *
     * @param keys key 集合
     * @return key → 三态结果
     */
    Map<CacheKey, Lookup<V>> lookupAll(Collection<CacheKey> keys);

    /**
     * 一次合并 I/O 返回<strong>该 key</strong> 的版本与值。
     *
     * @param key key
     * @return 版本 + 三态结果
     */
    VersionedLookup<V> lookupWithRevision(CacheKey key);

    /**
     * 批量版本化读。
     *
     * @param keys key 集合
     * @return key → 版本 + 三态结果
     */
    Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys);

    /**
     * 指定 key 的当前版本；缺失返回 {@code 0}。用于诊断与测试。
     *
     * @param key key
     * @return 版本
     */
    long revision(CacheKey key);

    /**
     * 批量取版本，供编排层做<strong>批量对账</strong>：只取版本、不取数据值。
     *
     * <p>返回的 map 中<strong>缺失</strong>的 key 表示中央已不再持有它（被删除 / 清空 /
     * 数据已按 TTL 过期），编排层据此清除本地副本。存储实现应在读取时顺手清理自己的过期元数据
     * （例如 Redis 的 {@code __rev} 死 field），对编排层透明。</p>
     *
     * @param keys key 集合
     * @return key → 版本；缺失表示已失效
     */
    Map<CacheKey, Long> revisions(Collection<CacheKey> keys);

    /**
     * 写值并原子更新该 key 的版本。
     *
     * @param key   key
     * @param value 值
     * @param ttl   存活时间；{@code null} 表示默认
     * @return 该 key 的新版本
     */
    long putAndGetRevision(CacheKey key, V value, Duration ttl);

    /**
     * 写"已确认不存在"并更新版本。
     *
     * @param key key
     * @param ttl 存活时间；{@code null} 表示 {@code nullTtl}
     * @return 该 key 的新版本
     */
    long putNegativeAndGetRevision(CacheKey key, Duration ttl);

    /**
     * 原子"不存在才写入"。
     *
     * @param key   key
     * @param value 值
     * @param ttl   存活时间
     * @return 写入成功返回新版本；已存在返回 empty 且<strong>不</strong>更新版本
     */
    OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl);

    /**
     * 轻量探活；不抛异常，失败返回 {@code false}。
     *
     * @return 是否可用
     */
    boolean ping();

    /**
     * @return 配置
     */
    CentralCacheSpec spec();
}
