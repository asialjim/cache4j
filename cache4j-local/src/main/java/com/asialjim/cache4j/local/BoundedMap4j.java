package com.asialjim.cache4j.local;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * <strong>有界映射</strong>：容量满后按淘汰策略挤掉最老的一条。
 *
 * <p>为什么需要它：版本表、分区索引这类"对账簿记"原本用 {@code ConcurrentHashMap}，
 * 会随着"访问过的 key 全集"无限增长——而这些簿记服务的只是"本地持有的副本"
 * （上限 {@code maxSize}），所以它们的容量也该有同样的上限。</p>
 *
 * <h2>淘汰策略</h2>
 * <ul>
 *   <li>{@link Eviction#LRU}：淘汰<strong>最久未访问</strong>的一条。适合"保留热 key 的信息"，
 *       例如版本表——热 key 的版本必须留着，冷 key 的丢了可以重建。</li>
 *   <li>{@link Eviction#FIFO}：淘汰<strong>最早写入</strong>的一条。适合"只关心有哪些条目"，
 *       例如分区索引——访问不会改变它的优先级。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>所有方法都是<strong>方法级原子</strong>（内部单锁）。因此：</p>
 * <ul>
 *   <li>不要在本类的方法外做"先读后写"的组合操作——那不在同一个临界区里；</li>
 *   <li>需要遍历时用 {@link #forEach}（持锁遍历）或 {@link #snapshot}（拷贝后遍历），
 *       <strong>不要</strong>指望 {@code snapshot} 之外拿到的东西能安全并发遍历。</li>
 * </ul>
 *
 * <p>单锁是刻意选择：簿记的访问频率低于主缓存，先保证正确与简单；
 * 若压测显示它成为瓶颈，再引入分段（与 {@code HashMapLocalCache} 同一思路）。</p>
 *
 * <p><strong>不实现 {@code Map} 接口</strong>：{@code Map} 的 {@code entrySet} / {@code keySet}
 * 会暴露可并发遍历的视图，与本类的加锁模型冲突；调用方也用不到
 * {@code merge} / {@code replaceAll} 那批默认方法。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class BoundedMap4j<K, V> {

    /**
     * 淘汰策略。
     */
    public enum Eviction {
        /**
         * 最久未访问先淘汰（{@code get} 也算访问）。
         */
        LRU,
        /**
         * 最早写入先淘汰（访问不影响顺序）。
         */
        FIFO
    }

    /**
     * 单次淘汰后的回调：可以据此把"已被挤出簿记"的 key 一并从别处清掉。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    @FunctionalInterface
    public interface EvictionListener<K, V> {
        /**
         * @param key   被淘汰的 key
         * @param value 被淘汰的值
         */
        void onEvicted(K key, V value);
    }

    private final int capacity;
    private final LinkedHashMap<K, V> map;
    private final Object lock = new Object();
    private final LongAdder evictedCount = new LongAdder();
    private volatile EvictionListener<K, V> evictionListener;

    private BoundedMap4j(int capacity, Eviction eviction) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(Math.max(capacity, 16), 0.75f, eviction == Eviction.LRU);
    }

    /**
     * @param capacity 容量上限，必须 ≥ 1
     * @param <K>      键类型
     * @param <V>      值类型
     * @return LRU 有界映射
     */
    public static <K, V> BoundedMap4j<K, V> lru(int capacity) {
        return new BoundedMap4j<>(requirePositive(capacity), Eviction.LRU);
    }

    /**
     * @param capacity 容量上限，必须 ≥ 1
     * @param <K>      键类型
     * @param <V>      值类型
     * @return FIFO 有界映射
     */
    public static <K, V> BoundedMap4j<K, V> fifo(int capacity) {
        return new BoundedMap4j<>(requirePositive(capacity), Eviction.FIFO);
    }

    /**
     * 注册淘汰回调；重复注册以最后一次为准。
     *
     * @param listener 回调；{@code null} 表示取消
     * @return this
     */
    public BoundedMap4j<K, V> onEvicted(EvictionListener<K, V> listener) {
        this.evictionListener = listener;
        return this;
    }

    /**
     * @param key 键
     * @return 值；不存在返回 {@code null}
     */
    public V get(K key) {
        synchronized (lock) {
            return map.get(key);        // accessOrder=true 时这一步会刷新 LRU 顺序
        }
    }

    /**
     * 写入一条；容量满时淘汰最老的一条。
     *
     * @param key   键
     * @param value 值
     * @return 该 key 原有的值；不存在返回 {@code null}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V previous;
        List<Map.Entry<K, V>> evicted;
        synchronized (lock) {
            previous = map.put(key, value);
            evicted = trim();
        }
        fireEvicted(evicted);
        return previous;
    }

    /**
     * 批量写入：<strong>整批共用一个临界区</strong>，且每条写入后立即按需淘汰，
     * 所以中途容量也不会被瞬时打穿。
     *
     * <p>淘汰回调在锁外逐条触发——回调里可以安全地回调本类或做别的事。</p>
     *
     * @param entries 待写入的键值对
     */
    public void putAll(Map<? extends K, ? extends V> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return;
        }
        List<Map.Entry<K, V>> evicted = new ArrayList<>(Math.min(entries.size(), 16));
        synchronized (lock) {
            entries.forEach((key, value) -> {
                map.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
                evicted.addAll(trim());
            });
        }
        fireEvicted(evicted);
    }

    /**
     * 仅当 key 不存在时写入。
     *
     * @param key   键
     * @param value 值
     * @return 该 key 原有的值；本次写入成功返回 {@code null}
     */
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V existing;
        List<Map.Entry<K, V>> evicted = List.of();
        synchronized (lock) {
            existing = map.putIfAbsent(key, value);
            if (existing == null) {
                evicted = trim();
            }
        }
        fireEvicted(evicted);
        return existing;
    }

    /**
     * 不存在则用 {@code mappingFunction} 算一个并写入。
     *
     * <p>{@code mappingFunction} 在锁内执行：它应该只做纯计算，不要回调本类，
     * 也不要长时间阻塞。</p>
     *
     * @param key             键
     * @param mappingFunction 值生成函数
     * @return 现存值或新算出的值
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        List<Map.Entry<K, V>> evicted = List.of();
        V result;
        synchronized (lock) {
            V current = map.get(key);
            if (current != null) {
                return current;
            }
            result = Objects.requireNonNull(mappingFunction.apply(key), "mappingFunction 返回 null");
            map.put(key, result);
            evicted = trim();
        }
        fireEvicted(evicted);
        return result;
    }

    /**
     * @param key 键
     * @return 该 key 原有的值；不存在返回 {@code null}
     */
    public V remove(K key) {
        synchronized (lock) {
            return map.remove(key);
        }
    }

    /**
     * @param key 键
     * @return 是否存在
     */
    public boolean containsKey(K key) {
        synchronized (lock) {
            return map.containsKey(key);
        }
    }

    /**
     * @return 当前条目数（≤ 容量）
     */
    public int size() {
        synchronized (lock) {
            return map.size();
        }
    }

    /**
     * @return 是否为空
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * @return 容量上限
     */
    public int capacity() {
        return capacity;
    }

    /**
     * @return 累计淘汰条数（观测用）
     */
    public long evictedCount() {
        return evictedCount.sum();
    }

    /**
     * 清空全部条目（不计入淘汰数）。
     */
    public void clear() {
        synchronized (lock) {
            map.clear();
        }
    }

    /**
     * 持锁遍历——遍历期间其它线程的写入会等待，因此可以安全地边遍历边处理。
     *
     * @param action 处理函数
     */
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action, "action");
        synchronized (lock) {
            map.forEach(action);
        }
    }

    /**
     * 拷贝一份当前内容，返回的 Map 与内部状态再无关联，可随意遍历。
     *
     * @return 快照（保序）
     */
    public Map<K, V> snapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(map);
        }
    }

    @Override
    public String toString() {
        return "BoundedMap4j{capacity=" + capacity + ", size=" + size()
                + ", evicted=" + evictedCount() + '}';
    }

    /**
     * 淘汰超出容量的部分。
     *
     * <p><strong>容量未超时不淘汰任何条目</strong>（返回空列表，零分配）——
     * 只有"加入新元素后总数大于构造时给的容量"才会挤掉最老的。</p>
     *
     * <p>用 {@code while} 而不是 {@code if}：常规路径每次只加一条，最多超 1；
     * 但一旦出现"一次加多条"的调用，{@code if} 会残留超标，{@code while} 能兜住。</p>
     *
     * @return 被淘汰的条目（按淘汰顺序）；未淘汰返回空列表
     */
    private List<Map.Entry<K, V>> trim() {
        if (map.size() <= capacity) {
            return List.of();
        }
        List<Map.Entry<K, V>> evicted = new ArrayList<>(2);
        while (map.size() > capacity) {
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            Map.Entry<K, V> eldest = it.next();
            it.remove();
            evictedCount.increment();
            evicted.add(eldest);
        }
        return evicted;
    }

    private void fireEvicted(List<Map.Entry<K, V>> evicted) {
        if (evicted.isEmpty()) {
            return;
        }
        EvictionListener<K, V> listener = this.evictionListener;
        if (listener == null) {
            return;
        }
        for (Map.Entry<K, V> entry : evicted) {
            listener.onEvicted(entry.getKey(), entry.getValue());
        }
    }

    private static int requirePositive(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 >= 1：" + capacity);
        }
        return capacity;
    }
}
