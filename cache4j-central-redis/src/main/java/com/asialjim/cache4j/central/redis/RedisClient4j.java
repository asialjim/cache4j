package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.Lookup;

import java.util.Map;
import java.util.Set;

/**
 * 与 Redis 交互的<strong>语义级</strong> SPI。
 *
 * <p>本模块只定义"需要 Redis 提供哪些能力"，<strong>不实现任何一条</strong>。
 * 具体实现由适配模块给出（{@code cache4j-spring-redis} 用 {@code RedisTemplate}），
 * 序列化与 Lua 脚本都在那一侧。</p>
 *
 * <h2>为什么不收字节、也不收脚本</h2>
 * <p>本接口刻意<strong>不出现 {@code byte[]}</strong>，也不出现 Lua 文本。原因是
 * Spring Data Redis 的 {@code RedisTemplate} 自带序列化器：值会先经它编码再落到 Redis。
 * 如果这里收字节，适配层只有两条路，都不好——</p>
 * <ul>
 *   <li>用 {@code opsForValue().set(bytes)}：字节再被序列化器包一层，
 *       Redis 里存的是"序列化后的字节数组"，别的客户端读不懂；</li>
 *   <li>绕过 {@code RedisTemplate} 直接用 {@code RedisConnection}：连接管理、序列化、
 *       异常翻译全部丢弃，{@code RedisTemplate} 白配了。</li>
 * </ul>
 * <p>所以值类型就是 {@code V}，负副本也用独立方法表达。至于"确认不存在"在 Redis 里
 * 长什么样（序列化一个哨兵、空串、特殊标记），以及"值与版本怎么原子写入"用哪段 Lua，
 * 全由实现决定。<strong>抽象层不假设任何字节格式，就不会破坏任何序列化器。</strong></p>
 *
 * <h2>为什么这些方法看起来"很业务"</h2>
 * <p>因为它们对应的是<strong>原子操作</strong>，而原子性只有实现侧才能保证。
 * 如果把 {@code set} 和 {@code hset __rev} 拆成两个方法暴露出来，
 * 中间就会有不一致窗口；拆成 {@code eval(script)} 又等于把脚本语义泄漏到抽象层。
 * 所以这里的方法粒度就是"一次原子操作"，名字直接说明它原子地做了什么。</p>
 *
 * <h2>实现者必须保证</h2>
 * <table border="1">
 *   <caption>契约</caption>
 *   <tr><th>项</th><th>要求</th></tr>
 *   <tr><td>异常</td><td>一律包成 {@link RedisAccessException4j}</td></tr>
 *   <tr><td>线程安全</td><td>可被多线程并发调用</td></tr>
 *   <tr><td>写值 + 版本</td><td>{@link #write} / {@link #writeNegative} 必须<strong>原子</strong>完成值写入与版本更新</td></tr>
 *   <tr><td>删除 + 版本</td><td>{@link #delete} 必须原子删掉数据与版本（删除对其他节点可观测）</td></tr>
 *   <tr><td>版本缺失</td><td>{@link #readVersions} 里中央不存在的 key <strong>不要放进结果</strong></td></tr>
 *   <tr><td>时间</td><td>{@link #timeSeconds} 与热度窗口必须用<strong>服务端</strong>时间</td></tr>
 *   <tr><td>清空</td><td>{@link #clearByPrefix} 分批进行，不得用长脚本阻塞实例</td></tr>
 * </table>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface RedisClient4j<V> extends AutoCloseable {

    /**
     * 读一个值，三态。
     *
     * @param dataKey 数据 key（来自 {@link RedisKeyLayout4j#dataKey}）
     * @return {@link Lookup#hit} / {@link Lookup#negative} / {@link Lookup#miss}
     */
    Lookup<V> read(String dataKey);

    /**
     * 写值，并与版本更新<strong>原子</strong>完成。
     *
     * @param dataKey       数据 key
     * @param value         值，不允许 {@code null}——负副本走 {@link #writeNegative}
     * @param ttlSeconds    存活秒数；{@code <= 0} 表示不过期
     * @param revisionKey   版本 HASH 的 key
     * @param revisionField 版本 field
     * @return 新版本
     */
    long write(String dataKey, V value, long ttlSeconds, String revisionKey, String revisionField);

    /**
     * 写"确认不存在"，同样与版本更新原子完成。
     *
     * @param dataKey       数据 key
     * @param ttlSeconds    存活秒数
     * @param revisionKey   版本 HASH 的 key
     * @param revisionField 版本 field
     * @return 新版本
     */
    long writeNegative(String dataKey, long ttlSeconds, String revisionKey, String revisionField);

    /**
     * 删除数据并删掉该 key 的版本，<strong>原子</strong>。
     *
     * @param dataKey       数据 key
     * @param revisionKey   版本 HASH 的 key
     * @param revisionField 版本 field
     */
    void delete(String dataKey, String revisionKey, String revisionField);

    /**
     * 批量取版本（对账用）：只取版本、不取数据值。
     *
     * @param revisionKey 版本 HASH 的 key
     * @param fields      业务 key 集合
     * @return 业务 key → 版本；<strong>中央不存在的 key 不要放进结果</strong>
     */
    Map<String, Long> readVersions(String revisionKey, Set<String> fields);

    /**
     * 按前缀清空（数据 + 版本 + 热度）。
     *
     * <p>前缀来自 {@link RedisKeyLayout4j#dataKeyPrefix}，以冒号结尾，
     * 天然只命中本 namespace 的 key。实现应分批删除，不得用长脚本阻塞实例。</p>
     *
     * @param prefix 前缀
     */
    void clearByPrefix(String prefix);

    /**
     * 热度累加，并返回<strong>合并两个窗口</strong>后的计数。
     *
     * <p>窗口怎么切由实现用服务端时间决定：{@code windowStart = floor(now / window) * window}，
     * 读时合并 {@code windowStart} 与 {@code windowStart - window}。</p>
     *
     * @param hotKeyPrefix  热度 key 前缀（来自 {@link RedisKeyLayout4j#hotKeyPrefix}）
     * @param windowSeconds 窗口长度（{@code hotWindow}）
     * @param deltas        业务 key → 增量
     * @return 业务 key → 合并后的计数
     */
    Map<String, Long> accumulateHotKey(String hotKeyPrefix, long windowSeconds, Map<String, Long> deltas);

    /**
     * 只读热度（不累加），同样合并两个窗口。
     *
     * @param hotKeyPrefix  热度 key 前缀
     * @param windowSeconds 窗口长度
     * @param fields        业务 key 集合
     * @return 业务 key → 计数
     */
    Map<String, Long> readHotKeyCounts(String hotKeyPrefix, long windowSeconds, Set<String> fields);

    /**
     * 服务端当前时间（秒）。
     *
     * <p><strong>必须是 Redis 的 {@code TIME}</strong>。多节点、跨时区、NTP 失准，
     * 只能靠"问同一个 Redis"消除。</p>
     *
     * @return 服务端时间（秒）
     */
    long timeSeconds();

    /**
     * 探活。
     *
     * @return 是否可用；失败返回 {@code false}，不抛异常
     */
    boolean ping();

    /**
     * 释放资源，必须幂等。
     */
    @Override
    void close();
}
