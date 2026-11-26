package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本地缓存 SPI。
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>本地 key 就是 {@link CacheKey}，value 是业务值 {@code V}——
 *       版本与"是否负副本"是<strong>实现内部</strong>的簿记，不越过这道接口；
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
public interface LocalCache<V> extends Cache4j<CacheKey, V> {

    /**
     * @return 策略
     */
    LocalStrategy strategy();

    /**
     * @return {@code NONE} 返回 {@code false}；{@code STATIC} / {@code DYNAMIC} 返回 {@code true}
     */
    boolean available();

    /**
     * 条件写入：本地已有<strong>更新</strong>版本时不覆盖。
     *
     * <p>回填用它消除"读版本 → 写副本"之间的竞态。{@code revision} 由调用方从<strong>中央</strong>
     * 读到的版本给出——本地只需要知道"这份值是哪一版"，不必自己查版本。</p>
     *
     * <p>默认实现返回 {@code false}（不接）：{@code NONE} 策略不存任何东西，
     * 于是"写不进去"这件事不需要门面做任何分支判断。</p>
     *
     * @param key      key
     * @param value    值
     * @param revision 值的版本；{@code 0} 表示"不比较，直接写"
     * @param ttl      TTL（可空）
     * @return 是否写入
     */
    default boolean putIfNewer(CacheKey key, V value, long revision, Duration ttl) {
        return false;
    }

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
