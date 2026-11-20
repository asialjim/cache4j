package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.Cache;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本地缓存 SPI。
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>本地 key 就是 {@link CacheKey}，value 是 {@link LocalEntry}（值 + 版本 + 种类）；
 *       失效按 key 覆盖/删除，不存在"整代切换"；</li>
 *   <li>{@link #access} 接收逻辑 key：频率统计与热点身份只与 key 有关，与版本无关；</li>
 *   <li>{@code lookup} <strong>不记录频率</strong>，频率只由 {@link #access} 记录，这样编排层能
 *       明确控制"一次业务读只记一次"；</li>
 *   <li>{@link #access} 必须在 {@code lookup} 之前调用，{@code DYNAMIC} 依赖这个顺序完成
 *       "先升热、再回填"；</li>
 *   <li>所有实现线程安全，{@code close} 幂等。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface LocalCache<V> extends Cache<CacheKey, LocalEntry<V>> {

    /**
     * @return 策略
     */
    LocalStrategy strategy();

    /**
     * @return {@code NONE} 返回 {@code false}；{@code STATIC} / {@code DYNAMIC} 返回 {@code true}
     */
    boolean available();

    /**
     * 便捷读：命中返回条目，未命中返回 {@code null}。
     *
     * @param key key
     * @return 条目或 {@code null}
     */
    default LocalEntry<V> entry(CacheKey key) {
        Lookup<LocalEntry<V>> lookup = lookup(key);
        return lookup instanceof Lookup.Hit<LocalEntry<V>> hit ? hit.value() : null;
    }

    /**
     * 内部读：与 {@link #entry} 语义相同，但<strong>不递增任何存储层统计</strong>
     * （{@code STORE_HIT} / {@code STORE_MISS}）。
     *
     * <p>编排层在回填前比对版本、对账时比对版本都用它——那些是内部动作，
     * 不该混进"本地命中率"这种对外诊断指标。</p>
     *
     * @param key key
     * @return 条目或 {@code null}
     */
    default LocalEntry<V> peek(CacheKey key) {
        return entry(key);
    }

    /**
     * 条件写入：仅当本地没有副本、或现有副本的版本<strong>不大于</strong>新版本时才写入。
     *
     * <p>回填用它消除"读版本 → 写副本"之间的竞态：两个线程并发回填同一个 key 时，
     * 旧版本不会覆盖新版本。默认实现是"先读后写"（非原子），本地存储实现应覆盖成原子版本。</p>
     *
     * @param key   key
     * @param value 条目
     * @param ttl   TTL（可空）
     * @return 是否写入
     */
    default boolean putIfNewer(CacheKey key, LocalEntry<V> value, Duration ttl) {
        LocalEntry<V> current = peek(key);
        if (current != null && current.revision() > value.revision()) {
            return false;
        }
        put(key, value, ttl);
        return true;
    }

    /**
     * 当前持有的全部 key（≤ {@code maxSize}）。对账需要它：一次 {@code HMGET} 只取本地关心的 key。
     *
     * <p>返回快照即可，不要求强一致。</p>
     *
     * @return key 集合快照
     */
    Set<CacheKey> keys();

    /**
     * 记录一次访问（按逻辑 key）。
     *
     * @param key key
     * @return {@code STATIC} 返回 {@code true}；{@code DYNAMIC} 返回"本次访问后是否处于热点状态"；
     * {@code NONE} 返回 {@code false}
     */
    boolean access(CacheKey key);

    /**
     * {@code DYNAMIC} 的访问驱动热同步：{@code STATIC} / {@code NONE} 为空实现。
     *
     * <p>到点且抢到门控时，一次性上报"当前 key 所属分区"的已热 key 与预选 key。</p>
     *
     * @param key 当前访问的 key
     */
    default void hotSync(CacheKey key) {
    }

    /**
     * 本地实现的自我维护：整批 TTL 清理 / 容量淘汰 / 冷却集合整批清理。
     *
     * <p>核心编排不创建调度线程，也<strong>不保证</strong>调用它；所有"必须发生"的维护
     * （草图旋转、冷却清扫、分区桶增删）都在 {@link #access} 或升/降热事件里惰性完成。</p>
     */
    void maintain();

    /**
     * @return 配置
     */
    LocalCacheSpec spec();

    /**
     * 本地是否会接受对该 key 的写入（<strong>不实际写入</strong>）。
     *
     * <p>编排层用它来准确描述"本地拒绝写入"的日志语义：
     * 调用方写入与内部回填是两件事，日志里不能都写成"写"。</p>
     *
     * @param key key
     * @return 是否会被接受；默认等于 {@link #available()}
     */
    default boolean accepts(CacheKey key) {
        return available();
    }

    /**
     * 注册「本地条目已消失」的监听器。
     *
     * <p>触发场景：<strong>TTL 过期</strong>、<strong>容量淘汰</strong>、<strong>显式失效</strong>
     * （{@link #evict}）。<strong>不</strong>为「覆盖写」和「整批清空」（{@link #clear}）触发——
     * 前者条目仍在，后者由调用方整体清理。</p>
     *
     * <p>编排层用它同步"key 的分区号 / 分区索引"两张表，避免过期条目在下一次对账前一直残留。
     * 实现可以惰性触发（例如哈希表实现在读时才发现过期），但<strong>一旦发现就必须回调</strong>。</p>
     *
     * @param listener 监听器，接收已消失的 key；重复注册以最后一次为准
     */
    default void onEntryRemoved(Consumer<CacheKey> listener) {
    }

    /**
     * 热 key 协调是否已就绪。
     *
     * <p>{@code DYNAMIC + coordination=GLOBAL} 且未提供 {@code HotKeyCoordinator} 时必须返回
     * {@code false}，由编排层在构建阶段判定为启动失败（不静默退化成本地协调）。</p>
     *
     * @return 是否就绪；默认 {@code true}
     */
    default boolean coordinationReady() {
        return true;
    }

    @Override
    default String name() {
        return spec().namespace();
    }
}
