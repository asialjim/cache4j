package com.asialjim.cache4j.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * <strong>有界集合</strong>：去重 + 容量上限，满后按淘汰策略挤出最老的一条。
 *
 * <p>为什么不能用 {@code Queue}：本类的使用场景（分区索引）<strong>每次访问都要 add 同一个 key</strong>，
 * 而队列<strong>不去重</strong>——同一个热 key 访问一千次就会把整个容量吃满，
 * 把其它 key 全挤出去。另外队列的"删除任意元素"是 {@code O(n)}，而这里需要按 key 精确移除（条目过期时）。</p>
 *
 * <p>所以"去重 + 有界 + FIFO 淘汰 + {@code O(1)} 删除"这四条同时满足的，
 * JDK 里只有 {@code LinkedHashSet}（或 {@code newSetFromMap(LinkedHashMap)}）。</p>
 *
 * <h2>淘汰策略</h2>
 * <ul>
 *   <li>{@link Eviction#FIFO}：淘汰最早插入的一条 —— 分区索引只关心"有哪些 key"，
 *       访问不该改变优先级，所以默认 FIFO。</li>
 *   <li>{@link Eviction#LRU}：淘汰最久未访问的一条 —— 若某个场景确实需要"保留热点"，
 *       用 {@link #lru(int)}；此时 {@link #contains} 也算一次访问。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>所有方法方法级原子（内部单锁）。需要遍历时用 {@link #forEach}（持锁）或
 * {@link #snapshot}（拷贝），<strong>不要</strong>拿到视图后并发遍历——
 * {@code LinkedHashSet} 的结构在并发修改下可能被写坏，表现为死循环而不是异常。</p>
 *
 * <p><strong>不实现 {@code Set} 接口</strong>：理由同 {@link BoundedMap4j}——
 * {@code iterator()} 会暴露一个无法安全并发使用的视图。</p>
 *
 * @param <E> 元素类型
 * @author Asial Jim
 */
public final class BoundedSet4j<E> {

    /**
     * 淘汰策略。
     */
    public enum Eviction {
        /**
         * 最早插入先淘汰。
         */
        FIFO,
        /**
         * 最久未访问先淘汰（{@code contains} 也算访问）。
         */
        LRU
    }

    /**
     * 单次淘汰后的回调。
     *
     * @param <E> 元素类型
     */
    @FunctionalInterface
    public interface EvictionListener<E> {
        /**
         * @param element 被淘汰的元素
         */
        void onEvicted(E element);
    }

    private final int capacity;
    private final Eviction eviction;
    private final LinkedHashSet<E> set = new LinkedHashSet<>();
    private final Object lock = new Object();
    private final LongAdder evictedCount = new LongAdder();
    private volatile EvictionListener<E> evictionListener;

    private BoundedSet4j(int capacity, Eviction eviction) {
        this.capacity = capacity;
        this.eviction = eviction;
    }

    /**
     * @param capacity 容量上限，必须 ≥ 1
     * @param <E>      元素类型
     * @return FIFO 有界集合
     */
    public static <E> BoundedSet4j<E> fifo(int capacity) {
        return new BoundedSet4j<>(requirePositive(capacity), Eviction.FIFO);
    }

    /**
     * @param capacity 容量上限，必须 ≥ 1
     * @param <E>      元素类型
     * @return LRU 有界集合
     */
    public static <E> BoundedSet4j<E> lru(int capacity) {
        return new BoundedSet4j<>(requirePositive(capacity), Eviction.LRU);
    }

    /**
     * 注册淘汰回调；重复注册以最后一次为准。
     *
     * @param listener 回调；{@code null} 表示取消
     * @return this
     */
    public BoundedSet4j<E> onEvicted(EvictionListener<E> listener) {
        this.evictionListener = listener;
        return this;
    }

    /**
     * 加入一个元素；已存在时不改变顺序（FIFO 下），容量满则淘汰最老的一条。
     *
     * @param element 元素
     * @return 本次是否新增（{@code false} = 已存在）
     */
    public boolean add(E element) {
        Objects.requireNonNull(element, "element");
        List<E> evicted = List.of();
        boolean added;
        synchronized (lock) {
            added = set.add(element);
            if (added) {
                evicted = trim();
            } else if (eviction == Eviction.LRU) {
                // 已存在也算一次"访问"：LRU 下要把它移到末尾，否则 add 走的路径不刷新顺序，
                // 整个 lru 模式会退化成 fifo
                set.remove(element);
                set.add(element);
            }
        }
        fireEvicted(evicted);
        return added;
    }

    /**
     * 批量加入。
     *
     * @param elements 元素集合
     * @return 本次新增的条数
     */
    public int addAll(Iterable<? extends E> elements) {
        Objects.requireNonNull(elements, "elements");
        int added = 0;
        // 逐个 add：每个都走同一把锁与同一套淘汰逻辑，避免一次塞入把容量瞬间打穿
        for (E element : elements) {
            if (add(element)) {
                added++;
            }
        }
        return added;
    }

    /**
     * @param element 元素
     * @return 是否存在（LRU 下这算一次访问）
     */
    public boolean contains(E element) {
        Objects.requireNonNull(element, "element");
        synchronized (lock) {
            boolean exists = set.contains(element);
            if (exists && eviction == Eviction.LRU) {
                // 重新插入以把它移到末尾——LinkedHashSet 没有 accessOrder，只能这样刷新顺序
                set.remove(element);
                set.add(element);
            }
            return exists;
        }
    }

    /**
     * 只判断存在性，<strong>不</strong>刷新 LRU 顺序。
     *
     * <p>给纯观测/查询用：{@link #contains} 在 LRU 下会移动元素，
     * 于是"查一下"就变成了"访问一次"，一个监控轮询就能让被查的 key 免于被淘汰。
     * 真正的访问路径仍然该用 {@link #contains}。</p>
     *
     * @param element 元素
     * @return 是否存在；不改变淘汰顺序
     */
    public boolean peek(E element) {
        Objects.requireNonNull(element, "element");
        synchronized (lock) {
            return set.contains(element);
        }
    }

    /**
     * @param element 元素
     * @return 是否原本存在
     */
    public boolean remove(E element) {
        Objects.requireNonNull(element, "element");
        synchronized (lock) {
            return set.remove(element);
        }
    }

    /**
     * @return 当前元素数（≤ 容量）
     */
    public int size() {
        synchronized (lock) {
            return set.size();
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
     * 清空（不计入淘汰数）。
     */
    public void clear() {
        synchronized (lock) {
            set.clear();
        }
    }

    /**
     * 持锁遍历——遍历期间其它线程的写入会等待。
     *
     * @param action 处理函数
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action, "action");
        synchronized (lock) {
            set.forEach(action);
        }
    }

    /**
     * 拷贝一份当前元素（保序），返回后可随意遍历。
     *
     * @return 快照
     */
    public Set<E> snapshot() {
        synchronized (lock) {
            return new LinkedHashSet<>(set);
        }
    }

    @Override
    public String toString() {
        return "BoundedSet4j{capacity=" + capacity + ", eviction=" + eviction
                + ", size=" + size() + ", evicted=" + evictedCount() + '}';
    }

    /**
     * 淘汰超出容量的部分。
     *
     * <p><strong>容量未超时不淘汰任何元素</strong>（返回空列表，零分配）——
     * 只有"加入新元素后总数大于构造时给的容量"才会挤掉最老的。</p>
     *
     * <p>用 {@code while} 而不是 {@code if}：常规路径每次只加一个，最多超 1；
     * 但一旦出现一次加多个的调用，{@code if} 会残留超标。</p>
     *
     * @return 被淘汰的元素（按淘汰顺序）；未淘汰返回空列表
     */
    private List<E> trim() {
        if (set.size() <= capacity) {
            return List.of();
        }
        List<E> evicted = new ArrayList<>(2);
        while (set.size() > capacity) {
            Iterator<E> it = set.iterator();
            E eldest = it.next();
            it.remove();
            evictedCount.increment();
            evicted.add(eldest);
        }
        return evicted;
    }

    private void fireEvicted(List<E> evicted) {
        if (evicted.isEmpty()) {
            return;
        }
        EvictionListener<E> listener = this.evictionListener;
        if (listener == null) {
            return;
        }
        for (E element : evicted) {
            listener.onEvicted(element);
        }
    }

    private static int requirePositive(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 >= 1：" + capacity);
        }
        return capacity;
    }
}
