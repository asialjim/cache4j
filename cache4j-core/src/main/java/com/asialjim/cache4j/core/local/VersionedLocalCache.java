package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheKey;

import java.util.Set;
import java.util.function.Consumer;

/**
 * <strong>有版本概念的</strong>本地缓存。
 *
 * <p>层次关系（能力按需分层，不是三个策略的并集）：</p>
 * <pre>
 * LocalCache&lt;V&gt;                       交集：lookup / put / putIfAbsent / evict / clear
 * ├─ NoneLocalCache&lt;V&gt;                就停在交集上——它不存值，也就没有版本可言
 * └─ VersionedLocalCache&lt;V&gt;           交集 + 版本对账所需的簿记能力
 *    ├─ StaticLocalCache&lt;V&gt;
 *    └─ DynamicLocalCache&lt;V&gt;
 * </pre>
 *
 * <p><strong>{@code NONE} 拿不到本接口</strong>：它没有版本、没有副本可对账。
 * 这正是"交集"与"并集"的差别——并集会把 {@code keys()} 塞给一个从不存 key 的实现。</p>
 *
 * <p>对账（{@code Reconciler}）需要的两个能力都在这里：</p>
 * <ul>
 *   <li>{@link #keys()} —— 本地现在持有哪些 key（对账要知道问中央哪些版本）；</li>
 *   <li>{@link #onEntryRemoved} —— 条目消失的通知（对账索引要跟着收缩，否则只增不减）。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public interface VersionedLocalCache<V> extends LocalCache<V> {

    /**
     * 当前持有的全部 key（≤ {@code maxSize}）。
     *
     * <p>对账需要它：一次批量取版本只问本地关心的 key。返回快照即可，不要求强一致。</p>
     *
     * @return key 集合快照
     */
    Set<CacheKey> keys();

    /**
     * 注册「本地条目已消失」的监听器。
     *
     * <p>触发场景：<strong>TTL 过期</strong>、<strong>容量淘汰</strong>、<strong>显式失效</strong>
     * （{@link #evict}）。<strong>不</strong>为「覆盖写」和「整批清空」（{@link #clear}）触发。</p>
     *
     * <p>对账用它同步"key 的分区号 / 分区索引"两张表，避免过期条目在下一次对账前一直残留。</p>
     *
     * @param listener 监听器，接收已消失的 key；重复注册以最后一次为准
     */
    default void onEntryRemoved(Consumer<CacheKey> listener) {
    }
}
