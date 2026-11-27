package com.asialjim.cache4j.local;

import com.asialjim.cache4j.Cache4jHotKeyDetector;
import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Cache4jKeyVersion;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.HotKeyStatus;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@code DYNAMIC} 策略的本地缓存骨架：<strong>STATIC 的版本管理 + 热 key 准入</strong>。
 *
 * <p>版本管理全部继承自 {@link AbstractVersionedLocalCache4j}（版本表、分区索引、对账、
 * {@code final keyVersion}），本类只加"谁有资格待在本地"。最终子类只需要提供存储读写。</p>
 *
 * <h2>五条设计理念，与父类逐条对应</h2>
 * <table border="1">
 *   <caption>版本管理 vs 热 key</caption>
 *   <tr><th>理念</th><th>父类（版本）</th><th>本类（热 key）</th></tr>
 *   <tr><td>状态有界</td><td>{@code BoundedMap4j} / {@code BoundedSet4j}</td><td>同款容器，容量挂钩热 key 配置</td></tr>
 *   <tr><td>{@code final} 入口</td><td>{@code keyVersion}</td><td><b>{@link #access}</b></td></tr>
 *   <tr><td>懒维护（无调度线程）</td><td>对账挂在读路径</td><td>升降温 / 冷却清扫 / 上报都挂在读路径</td></tr>
 *   <tr><td>时间信号量</td><td>{@code reconcileTimeMap}</td><td>{@code nextSyncAt}</td></tr>
 *   <tr><td>抽象钩子</td><td>{@code delete(key, strategy)}</td><td>{@link Cache4jHotKeyDetector}（上报）</td></tr>
 * </table>
 *
 * <h2>读路径：一次访问只统计一次频次</h2>
 * <pre>
 * lookup / get
 *   └─ read(key, strategy)                     ← 唯一入口
 *        ├─ access(logical, strategy)          ← 唯一一次频次统计，返回 admitted
 *        ├─ syncIfNeeded(strategy)             ← 到点上报（懒）
 *        ├─ admitted ? keyVersion(...) : Miss  ← 冷 key 连版本都不查
 *        └─ admitted ? load(versionedKey) : Miss
 * </pre>
 *
 * <p>{@code admitted} 从 {@link #read} 的记录里带出来供回填使用，<strong>绝不重复调用 {@link #access}</strong>——
 * 那会把一次读算成两次频次，让冷 key 提前升热。</p>
 *
 * <h2>两段式判定（候选宁多勿少，升热必须精确）</h2>
 * <ol>
 *   <li><strong>候选</strong>：{@link FrequencySketch} 共享草图，固定内存、只会高估——
 *       漏判热点会让流量全打到中央，所以这里多判是安全方向；</li>
 *   <li><strong>升热</strong>：只有达到候选阈值的 key 才建 {@link HotWindow} 精确计数——
 *       误升热会挤掉真热点，所以这一阶段必须准。</li>
 * </ol>
 *
 * <h2>降热</h2>
 * <p>{@code coordination=GLOBAL} 时只认中央的 {@link HotKeyStatus#COOLING}：单节点看到的
 * 只是局部流量。{@code coordination=LOCAL}（本类的探测器为 {@code null}）时，降热由冷却期 +
 * 窗口衰减共同决定——达到升热阈值才升，冷却期内不升。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public abstract class AbstractDynamicLocalCache4j<V> extends AbstractVersionedLocalCache4j<V> {

    /**
     * 中央热 key 探测器；{@code null} 等价于 {@code coordination=LOCAL}（不上报、不查裁决）。
     */
    protected final Cache4jHotKeyDetector centralCache4jHotKeyDetector;

    // ------------------------------------------------------------------ 热 key 状态（全部有界）

    /**
     * 热 key 集合：容量 = {@code maxHotKeys}，LRU（访问刷新，被挤出的是最久没访问的热 key）。
     */
    private final Map<String, BoundedSet4j<String>> hotKeys = new ConcurrentHashMap<>();

    /**
     * 精确窗口表：容量 = {@code candidateCapacity}，LRU。
     * 只为"达到候选阈值"的 key 建，长尾 key 只占共享草图的固定内存。
     */
    private final Map<String, BoundedMap4j<String, HotWindow>> windows = new ConcurrentHashMap<>();

    /**
     * 共享 CMS 草图：固定内存，与访问过的 key 数量无关。
     */
    private final Map<String, FrequencySketch> sketches = new ConcurrentHashMap<>();

    /**
     * 冷却集合：降热后的一段时间内拒绝升热，抑制"降了立刻又升"的抖动。
     */
    private final Map<String, BoundedMap4j<String, Long>> cooldowns = new ConcurrentHashMap<>();

    /**
     * 待上报增量：key → 累计访问次数，容量 = {@code hotReportBatchMax}。
     */
    private final Map<String, BoundedMap4j<String, AtomicLong>> pendingDelta = new ConcurrentHashMap<>();

    /**
     * 上报信号量：下一次允许上报的时刻（毫秒）。
     */
    /**
     * 本地降热扫描的时间信号量：容量无关，每 namespace 一个到期时刻。
     *
     * <p>与 {@link #nextSyncAt} 分开，是因为本地降热与上报是两条独立的节奏：
     * 前者由 {@code coolQps} 驱动、在 {@code coordination=LOCAL} 下也要跑，
     * 后者只在有中央探测器时才有意义。</p>
     */
    private final Map<String, Long> nextLocalSweepAt = new ConcurrentHashMap<>();

    /**
     * 连续收到 {@code COOLING} 的次数：容量 = {@code maxHotKeys}，FIFO。
     *
     * <p>中央偶尔抖一次不该让一个真热点立刻降热，所以要连续 {@code globalDemoteWindows}
     * 次都判降温才真正降。收到 {@code HOT} 即清零。</p>
     */
    private final Map<String, BoundedMap4j<String, AtomicInteger>> coolStreaks = new ConcurrentHashMap<>();

    /**
     * 最后一次收到中央表态的时刻：容量 = {@code maxHotKeys}，FIFO。
     *
     * <p>上报过但中央连续 {@code globalStaleWindows} 个周期都不表态的 key 视为陈旧
     * ——中央那边已经不认识它了（例如换了 Redis 实例、策略被整体重置），本地不该继续当热点。</p>
     */
    private final Map<String, BoundedMap4j<String, Long>> lastVerdicts = new ConcurrentHashMap<>();

    private final Map<String, Long> nextSyncAt = new ConcurrentHashMap<>();

    /**
     * 上报门控：同一 namespace 同时只有一个线程上报。
     */
    private final Map<String, AtomicBoolean> syncing = new ConcurrentHashMap<>();

    /**
     * @param versionDetector 中央版本探测器
     * @param hotKeyDetector  中央热 key 探测器；{@code null} 表示 {@code coordination=LOCAL}
     */
    protected AbstractDynamicLocalCache4j(Cache4jKeyVersionDetector versionDetector,
                                          Cache4jHotKeyDetector hotKeyDetector) {
        super(versionDetector);
        this.centralCache4jHotKeyDetector = hotKeyDetector;
    }

    // ------------------------------------------------------------------ 存储钩子（交给最终子类）

    /**
     * 读存储。{@code key} 已经带上版本（{@link Cache4jKey#localKey()} 可用）。
     *
     * @param key 带版本的 key
     * @return 三态结果
     */
    protected abstract Lookup<V> load(Cache4jKey key);

    /**
     * 写存储。
     *
     * @param key   带版本的 key
     * @param value 值，不为 {@code null}
     */
    protected abstract void store(Cache4jKey key, V value);

    /**
     * 写"确认不存在"的负副本。
     *
     * @param key 带版本的 key
     */
    protected abstract void storeNegative(Cache4jKey key);

    /**
     * 清空存储（{@link #clear} 时调用）。
     */
    protected abstract void clearStorage();

    // ------------------------------------------------------------------ StrategyCache4j

    @Override
    public final Lookup<V> lookup(Cache4jKey key, CacheStrategy strategy) {
        return read(key, strategy).lookup();
    }

    @Override
    public final V get(Cache4jKey key, Supplier<V> valueLoader, CacheStrategy strategy) {
        Read4j<V> result = read(key, strategy);
        if (!result.lookup().isMiss()) {
            // 正命中 / 负命中都直接返回，不回源也不回填
            return result.lookup().optional().orElse(null);
        }

        V value = valueLoader == null ? null : valueLoader.get();

        // 用 read 带出来的准入结论决定是否落本地——不再调 access（那会把一次读算成两次频次）
        if (result.admitted()) {
            Cache4jKey versioned = versioned(key, strategy);
            if (value == null) {
                storeNegative(versioned);
            } else {
                store(versioned, value);
            }
        }
        return value;
    }

    /**
     * <strong>空实现</strong>：{@code DYNAMIC} 下写操作不落本地。
     *
     * <p><strong>本地副本的唯一来源是读路径的回填</strong>：读 → 不热 → 查中央 → 中央命中 → 回填。
     * 写如果能落本地，就会产生"没经过读验证"的副本——而它对应的 key 可能根本不热，
     * 下一次读照样因准入不通过而 miss，白占容量。</p>
     *
     * <p>另外写不计读频次，也就写不出热度：hotkey 的唯一目的是减少对中央的<strong>读</strong>，
     * 写无论怎么做都无法减轻中央压力，所以"被写过"不能成为升热的理由。</p>
     *
     * <p>需要 read-your-write 请用 {@code STATIC} 策略。</p>
     */
    @Override
    public final void put(Cache4jKey key, V value, CacheStrategy strategy) {
        // DYNAMIC：写不落本地。本地副本只由读路径回填。
    }

    /**
     * <strong>空实现</strong>：{@code DYNAMIC} 策略下写操作不落本地。
     *
     * <p>三个理由：</p>
     * <ol>
     *   <li>写不计读频次，也就写不出热度——"同步写进去"不会让这个 key 变热；</li>
     *   <li>即使强行落本地，下一次读仍会因为不热而 miss，等于白占容量；</li>
     *   <li>"写完立刻本地可见"是 {@code STATIC} 的语义（{@code read-your-write} 需要它），
     *       {@code DYNAMIC} 本来就不保证。</li>
     * </ol>
     *
     * <p>因此这里显式留空并声明 {@code final}——避免子类"顺手实现"出一个违反策略语义的写入。</p>
     */
    @Override
    public final void putSync(Cache4jKey key, V value, CacheStrategy strategy) {
        // DYNAMIC：写不落本地。需要 read-your-write 请用 STATIC 策略。
    }

    @Override
    public final void evict(Cache4jKey key, CacheStrategy strategy) {
        // 只失效数据，不动热点身份：热点由"读频次"决定，与值是否新鲜无关
        delete(key.key(), strategy);
    }

    @Override
    public final void clear(CacheStrategy strategy) {
        String namespace = strategy.namespace();
        hotKeys.remove(namespace);
        windows.remove(namespace);
        FrequencySketch sketch = sketches.remove(namespace);
        if (sketch != null) {
            sketch.reset();
        }
        cooldowns.remove(namespace);
        pendingDelta.remove(namespace);
        nextSyncAt.remove(namespace);
        syncing.remove(namespace);
        clearStorage();
        // 版本表与分区索引由父类持有（private，无公开清理入口）：
        // 它们随访问重建、随过期收缩，这里不清也不会泄漏
    }

    @Override
    public final Map<String, Long> partitionVersion(Set<String> keys, CacheStrategy strategy) {
        return super.centralCache4jKeyVersionDetector.partitionVersion(keys, strategy);
    }

    // ------------------------------------------------------------------ 准入（final 入口）

    /**
     * 记录一次<strong>读</strong>并判定该 key 现在是否允许待在本地。
     *
     * <p>这是热 key 判定的唯一入口：{@code lookup} / {@code get} 都走 {@link #read}，
     * 而 {@link #read} 只调用本方法一次。写路径（{@code put}）也会调用它，因为
     * "写不计频次"这条规则要靠它来落地。</p>
     *
     * @param key      逻辑 key
     * @param strategy 缓存策略
     * @return 是否允许本地持有该 key
     */
    public final boolean access(String key, CacheStrategy strategy) {
        long now = System.currentTimeMillis();
        BoundedSet4j<String> hotSet = hotSet(strategy);

        // ① 已热：contains 在 LRU 模式下会把该 key 移到末尾，同时累计待上报增量
        if (hotSet.contains(key)) {
            addPendingDelta(key, strategy, 1L);
            return true;
        }

        // ② 懒维护：顺手清扫过期冷却
        sweepCooldown(strategy, now);

        // ③ 懒维护：按 coolQps 判定本地降热（每过一个窗口扫一次，摊销到单次读上接近零成本）
        sweepCoolIfNeeded(strategy, now);

        // ④ CMS 粗筛：共享草图，只会高估
        long approx = sketch(strategy).incrementAndEstimate(key, now);

        // ⑤ 精确窗口：达候选阈值才建窗口
        long count = preciseCount(key, strategy, now, approx);

        // ⑥ 升热判定：冷却期内即使频次够了也不升（抑制"降热→立刻又升热"）
        long hotThreshold = hotThresholdCount(strategy);
        if (!isCooling(key, strategy, now) && count >= hotThreshold) {
            promote(key, strategy, count, now);
            return true;
        }

        // ⑦ 未达升热阈值：低于候选阈值就把窗口也清掉，避免长尾 key 长期占用窗口表
        if (count < candidateCount(strategy)) {
            windows(strategy).remove(key);
        }
        return false;
    }

    /**
     * 该 key 当前是否热（<strong>不</strong>记频次）。
     *
     * <p>与 {@link #access} 的区别：`access` 有副作用（记一次访问），本方法纯查询。
     * 上层判断"要不要走本地"时用 {@code access}；只想"看一眼状态"时用本方法。</p>
     *
     * @param key      逻辑 key
     * @param strategy 缓存策略
     * @return 是否热
     */
    /**
     * 查询 key 当前是否被当作热点。
     *
     * <p>用 {@code peek} 而不是 {@code contains}：热集合是 LRU，{@code contains}
     * 会把元素移到末尾——那样一次监控轮询就等于一次"访问"，被查的 key 永远不会被挤出去。
     * 观测不该影响淘汰顺序。</p>
     */
    public final boolean hot(String key, CacheStrategy strategy) {
        return hotSet(strategy).peek(key);
    }

    /**
     * 降热：移出热集合、清窗口、进冷却、删存储。
     *
     * <p>由两个来源触发：中央裁决 {@link HotKeyStatus#COOLING}，或（LOCAL 模式下）
     * 窗口衰减到 `coolQps` 以下。{@code delete} 复用父类的抽象钩子——降热与过期
     * 是同一个动作："这条不该再待在本地了"。</p>
     *
     * @param key      逻辑 key
     * @param strategy 缓存策略
     */
    protected final void whenCooled(String key, CacheStrategy strategy) {
        hotSet(strategy).remove(key);
        windows(strategy).remove(key);
        pendingDelta(strategy).remove(key);
        // 降热后这两张表里的记录已经没有意义：连续的 COOLING 计数要重新开始，
        // 陈旧判定也不该再盯着一个已经不是热点的 key
        coolStreak(strategy).remove(key);
        lastVerdicts(strategy).remove(key);
        long until = System.currentTimeMillis() + cooldownMillis(strategy);
        cooldowns(strategy).put(key, until);
        delete(key, strategy);
    }

    // ------------------------------------------------------------------ 读路径

    /**
     * 一次读的完整结论：准入 + 三态结果。
     *
     * <p>{@code admitted} 必须带出去给"回填"用——如果回填时再调一次 {@link #access}，
     * 一次读会被算成两次频次，冷 key 会提前升热。</p>
     */
    private record Read4j<V>(boolean admitted, Lookup<V> lookup) {
    }

    private Read4j<V> read(Cache4jKey key, CacheStrategy strategy) {
        // ① 唯一一次频次统计
        boolean admitted = access(key.key(), strategy);

        // ② 到点上报（懒：只有读路径会推进信号量）
        syncIfNeeded(strategy);

        if (!admitted) {
            // 不热 = 未命中，天然表达"本地不服务这个 key"
            return new Read4j<>(false, Lookup.miss());
        }

        // ③ 热了才要版本：冷 key 连版本都不查，版本表不会被冷 key 占位
        Cache4jKey versioned = versioned(key, strategy);

        // ④ 存储读
        Lookup<V> result = load(versioned);
        return new Read4j<>(true, result == null ? Lookup.miss() : result);
    }

    /**
     * 把逻辑 key 换成带版本的 key：版本进 key，版本一变 key 就变，旧副本自然不命中。
     */
    private Cache4jKey versioned(Cache4jKey key, CacheStrategy strategy) {
        Cache4jKeyVersion version = keyVersion(key.key(), strategy);
        //noinspection ConstantValue
        if (Objects.isNull(version)) {
            return key;
        }
        return new Cache4jKey(key.namespace(), key.key(), version);
    }

    // ------------------------------------------------------------------ 判定细节

    /**
     * 取该 key 的窗口内精确计数。
     *
     * <p>窗口状态只为"可能成为热点"的 key 建立：CMS 粗筛（只会高估）达到候选阈值才建表，
     * 容量上限 {@code candidateCapacity}——长尾 key 只占共享草图的固定内存。</p>
     *
     * <p>建表时以"候选阈值"作为计数下限：既保留建表前已攒的访问量（否则低速热点要再攒
     * 一整个窗口才升热），又不会把 CMS 的高估值直接灌进精确判定（否则一次高估就能误升热）。</p>
     */
    private long preciseCount(String key, CacheStrategy strategy, long now, long approx) {
        BoundedMap4j<String, HotWindow> windowMap = windows(strategy);
        HotWindow existing = windowMap.get(key);
        if (existing != null) {
            return existing.incrementAndCount(now);
        }

        long candidateThreshold = candidateCount(strategy);
        if (approx < candidateThreshold) {
            // 连候选都不是：不建窗口，直接返回粗筛值（它低于阈值，不会误升热）
            return approx;
        }

        HotWindow created = new HotWindow(windowSlices(strategy), windowMillis(strategy));
        // 建表前的历史访问量写进窗口本身（而不是只抬高本次返回值）——
        // 否则下次 incrementAndCount 会从 1 重来，计数倒退，低速热点永远攒不够
        long seed = Math.max(0L, candidateThreshold - 1L);
        created.seed(now, seed);
        windowMap.put(key, created);
        return created.incrementAndCount(now);
    }

    private void promote(String key, CacheStrategy strategy, long count, long now) {
        hotSet(strategy).add(key);              // 容量满时 LRU 挤掉最久没访问的热 key
        cooldowns(strategy).remove(key);        // 升热后清冷却，避免刚升上就又被冷却挡住
        // 记录起点：陈旧判定需要一个基准时刻，否则"从未被中央表态过"的 key
        // 在表里查不到记录，就永远轮不到它
        lastVerdicts(strategy).put(key, now);
        addPendingDelta(key, strategy, Math.max(1L, count));
    }

    private boolean isCooling(String key, CacheStrategy strategy, long now) {
        BoundedMap4j<String, Long> cooldownMap = cooldowns(strategy);
        Long until = cooldownMap.get(key);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            cooldownMap.remove(key);
            return false;
        }
        return true;
    }

    private void sweepCooldown(CacheStrategy strategy, long now) {
        BoundedMap4j<String, Long> cooldownMap = cooldowns(strategy);
        // 遍历用 snapshot：BoundedMap4j.forEach 会持锁遍历，在里面 remove 自己会 CME
        cooldownMap.snapshot().forEach((key, until) -> {
            if (until != null && until <= now) {
                cooldownMap.remove(key);
            }
        });
    }

    private void addPendingDelta(String key, CacheStrategy strategy, long delta) {
        pendingDelta(strategy)
                .computeIfAbsent(key, k -> new AtomicLong())
                .addAndGet(Math.max(1L, delta));
    }

    // ------------------------------------------------------------------ 上报

    private void syncIfNeeded(CacheStrategy strategy) {
        Cache4jHotKeyDetector detector = this.centralCache4jHotKeyDetector;
        if (detector == null) {
            // coordination=LOCAL：不上报，降热由本地窗口 + 冷却决定
            return;
        }
        long now = System.currentTimeMillis();
        String namespace = strategy.namespace();
        Long next = nextSyncAt.get(namespace);
        if (next == null) {
            // 首次只设点，不做 I/O：冷启动的第一个请求不该多一次中央往返。
            // 与 reconcileNeed 的首次行为保持一致。
            nextSyncAt.put(namespace, now + syncIntervalMillis(strategy));
            return;
        }
        if (now < next) {
            return;
        }

        AtomicBoolean gate = syncing.computeIfAbsent(namespace, s -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) {
            // 同一 namespace 同时只有一个线程上报，其余直接跳过（到点后的下一次读再来）
            return;
        }

        Map<String, Long> batch = new LinkedHashMap<>();
        try {
            drainPendingDelta(strategy, batch);
            if (batch.isEmpty()) {
                // 没有增量也要判陈旧：中央长期不表态的 key 不能永远留在热集合里
                sweepStale(strategy, now);
                return;
            }
            Map<String, HotKeyStatus> verdict = report(detector, batch, strategy);
            if (verdict == null) {
                // 实现方违反了"不返回 null"的约定：当作没有结论，增量回填
                restorePendingDelta(strategy, batch);
                return;
            }
            applyVerdict(strategy, verdict, now);
            sweepStale(strategy, now);
        } catch (RuntimeException e) {
            // 上报失败不能丢热度：整批回填，等下一个周期再报
            restorePendingDelta(strategy, batch);
        } finally {
            gate.set(false);
            nextSyncAt.put(namespace, now + syncIntervalMillis(strategy));
        }
    }

    /**
     * 取出并清零待上报增量。{@code getAndSet(0)} 保证"上报失败回填"不会重复计数，
     * 且清零期间的新访问会累加到 0 之上，不会丢。
     */
    private void drainPendingDelta(CacheStrategy strategy, Map<String, Long> sink) {
        pendingDelta(strategy).forEach((key, adder) -> {
            if (adder == null) {
                return;
            }
            long value = adder.getAndSet(0L);
            if (value > 0L) {
                sink.put(key, value);
            }
        });
    }

    private void restorePendingDelta(CacheStrategy strategy, Map<String, Long> batch) {
        batch.forEach((key, value) -> addPendingDelta(key, strategy, value));
    }

    // ------------------------------------------------------------------ 状态容器（全部有界）

    private BoundedSet4j<String> hotSet(CacheStrategy strategy) {
        return hotKeys.computeIfAbsent(strategy.namespace(),
                s -> BoundedSet4j.lru(capacityOf(strategy, strategy.maxHotKeys(), 1_000)));
    }

    private BoundedMap4j<String, HotWindow> windows(CacheStrategy strategy) {
        return windows.computeIfAbsent(strategy.namespace(),
                s -> BoundedMap4j.lru(capacityOf(strategy, strategy.candidateCapacity(), 4_096)));
    }

    private FrequencySketch sketch(CacheStrategy strategy) {
        return sketches.computeIfAbsent(strategy.namespace(), s -> new FrequencySketch(
                intOf(strategy.sketchWidthBits(), 14),
                intOf(strategy.sketchDepth(), 4),
                windowMillis(strategy),
                System.currentTimeMillis()));
    }

    private BoundedMap4j<String, Long> cooldowns(CacheStrategy strategy) {
        return cooldowns.computeIfAbsent(strategy.namespace(),
                s -> BoundedMap4j.fifo(capacityOf(strategy, strategy.maxHotKeys(), 1_000)));
    }

    private BoundedMap4j<String, AtomicLong> pendingDelta(CacheStrategy strategy) {
        return pendingDelta.computeIfAbsent(strategy.namespace(),
                s -> BoundedMap4j.fifo(capacityOf(strategy, strategy.hotReportBatchMax(), 64)));
    }

    // ------------------------------------------------------------------ 策略取值（Supplier 可空兜底）

    private long hotThresholdCount(CacheStrategy strategy) {
        return longOf(strategy.hotThresholdQps(), 100L) * Math.max(1L, windowSeconds(strategy));
    }

    /**
     * 候选阈值（窗口内累计次数）。
     *
     * <pre>{@code
     * U = clamp( ceil(globalHotQps / globalClientEstimate), 1, hotThresholdQps - 1 )
     * }</pre>
     *
     * <p>必须严格小于升热阈值：否则任何到达 U 的 key 早已升热，候选桶恒空。
     * 两侧都是 QPS 口径，因此不需要窗口比例因子。</p>
     */
    private long candidateCount(CacheStrategy strategy) {
        long hotQps = longOf(strategy.hotThresholdQps(), 100L);
        long upper = Math.max(1L, hotQps - 1L);
        long candidateQps;
        boolean global = isGlobal(strategy);
        Long globalWindow = longOrNull(strategy.globalWindow());
        if (!global || globalWindow == null) {
            candidateQps = upper;
        } else {
            long estimate = Math.max(1L, intOf(strategy.globalClientEstimate(), 1));
            long share = (longOf(strategy.globalHotQps(), 100L) + estimate - 1L) / estimate;
            candidateQps = Math.max(1L, Math.min(share, upper));
        }
        return candidateQps * Math.max(1L, windowSeconds(strategy));
    }

    /**
     * 按 {@code hotReportPartitions} 把一批增量拆成若干次上报。
     *
     * <p>拆分的意义不是省流量，而是**控制单次请求的体积**：一次带了上千个 key 的
     * {@code report} 在跨网络调用时会变成尾延迟的来源。拆开之后每个子请求都小、都独立，
     * 任一子请求超时也只影响它那一份。</p>
     *
     * <p>拆分在同一轮内完成，不是"这轮报一个分区、下轮报另一个"——
     * 那会把一次上报的周期拉长成 {@code partitions} 倍。</p>
     *
     * @return 合并后的结论；任一分片违约（返回 {@code null}）则整体视为失败
     */
    private Map<String, HotKeyStatus> report(Cache4jHotKeyDetector detector,
                                             Map<String, Long> batch,
                                             CacheStrategy strategy) {
        int partitions = Math.max(1, intOf(strategy.hotReportPartitions(), 1));
        if (partitions <= 1 || batch.size() <= 1) {
            return detector.report(batch, strategy);
        }

        Map<Integer, Map<String, Long>> groups = new LinkedHashMap<>();
        batch.forEach((key, count) -> groups
                .computeIfAbsent(Math.floorMod(key.hashCode(), partitions), i -> new LinkedHashMap<>())
                .put(key, count));

        Map<String, HotKeyStatus> merged = new LinkedHashMap<>();
        for (Map<String, Long> group : groups.values()) {
            Map<String, HotKeyStatus> part = detector.report(group, strategy);
            if (part == null) {
                return null;
            }
            merged.putAll(part);
        }
        return merged;
    }

    /**
     * 落实中央的裁决。
     *
     * <ul>
     *   <li>{@code HOT}：清零连续降温计数；</li>
     *   <li>{@code COOLING}：累加计数，达到 {@code globalDemoteWindows} 才真正降热——
     *       中央偶发一次误判不该让真热点丢掉本地副本；</li>
     *   <li>{@code UNKNOWN} 或 {@code null}：不表态，既不清零也不累加。</li>
     * </ul>
     */
    private void applyVerdict(CacheStrategy strategy, Map<String, HotKeyStatus> verdict, long now) {
        int demoteWindows = Math.max(1, intOf(strategy.globalDemoteWindows(), 1));
        verdict.forEach((key, status) -> {
            if (status == null) {
                return;
            }
            switch (status) {
                case HOT -> {
                    coolStreak(strategy).remove(key);
                    // 明确表态：刷新"最后表态时刻"，陈旧判定重新计时
                    lastVerdicts(strategy).put(key, now);
                }
                case COOLING -> {
                    AtomicInteger streak = coolStreak(strategy).computeIfAbsent(key, k -> new AtomicInteger());
                    if (streak.incrementAndGet() >= demoteWindows) {
                        whenCooled(key, strategy);
                    }
                    lastVerdicts(strategy).put(key, now);
                }
                default -> {
                    // UNKNOWN：中央还没攒够数据，这**不算表态** —— 不能刷新时刻，
                    // 否则 globalStaleWindows 永远等不到"连续 N 个周期没吭声"
                }
            }
        });
    }

    /**
     * 清掉中央长期不表态的 key。
     *
     * <p>判据是 {@code globalStaleWindows} 个上报周期都没收到过 {@code HOT} / {@code COOLING}。
     * 这些 key 上报过、中央却不认，说明双方对"热点集合"的认知已经不一致
     * （换实例、策略被重置、或者中央侧丢数据），继续当热点只会白占本地内存。</p>
     */
    private void sweepStale(CacheStrategy strategy, long now) {
        long staleMillis = Math.max(1, intOf(strategy.globalStaleWindows(), 1)) * syncIntervalMillis(strategy);
        BoundedMap4j<String, Long> seen = lastVerdicts(strategy);
        // 遍历热 key 集合而不是 seen 的键集：从未被中发表态过的 key 不在 seen 里，
        // 只遍历 seen 会漏掉"升热后一直没人表态"这种最典型的陈旧情形。
        // snapshot 避免在遍历中 whenCooled -> remove 触发 CME
        for (String key : hotSet(strategy).snapshot()) {
            Long at = seen.get(key);
            if (at != null && now - at <= staleMillis) {
                continue;
            }
            whenCooled(key, strategy);
        }
    }

    /**
     * 按 {@code coolQps} 做本地降热扫描。
     *
     * <p>降热不能只等中央裁决：{@code coordination=LOCAL} 时根本没有裁决，
     * GLOBAL 模式下中央也可能因为上报间隔而滞后。所以本地要有一条独立判据——
     * 热 key 的窗口计数掉到 {@code coolQps × 窗口秒数} 以下，就说明它已经凉了。</p>
     *
     * <p>扫描周期 = 一个统计窗口，摊到单次读上几乎为零成本；同时用
     * {@link #nextLocalSweepAt} 做门控，避免每次读都遍历整个热集合。</p>
     */
    private void sweepCoolIfNeeded(CacheStrategy strategy, long now) {
        String namespace = strategy.namespace();
        Long next = nextLocalSweepAt.get(namespace);
        if (next != null && now < next) {
            return;
        }
        nextLocalSweepAt.put(namespace, now + windowMillis(strategy));

        long coolCount = coolCount(strategy);
        BoundedMap4j<String, HotWindow> windowMap = windows(strategy);
        // snapshot 避免在遍历中 whenCooled -> remove 触发 CME
        for (String key : hotSet(strategy).snapshot()) {
            HotWindow window = windowMap.get(key);
            long count = window == null ? 0L : window.count(now);
            if (count < coolCount) {
                whenCooled(key, strategy);
            }
        }
    }

    private boolean isGlobal(CacheStrategy strategy) {
        String coordination = strategy.coordination() == null ? null : strategy.coordination().get();
        return "GLOBAL".equalsIgnoreCase(coordination);
    }

    /**
     * 本地降热阈值（窗口内次数）。
     *
     * <pre>{@code
     * coolQps 本地视角：coolQps
     * GLOBAL 视角：min( coolQps, ceil(globalCoolQps / globalClientEstimate) )
     * }</pre>
     *
     * <p>取两者中更严格的一个：全局摊派后若每节点只剩几个 QPS，说明整个系统的流量都在退潮，
     * 本地不该再抱着这个 key 不放；反之全局还热、只是本节点分到的少了，摊派值会更大，
     * 此时以本地阈值为准。</p>
     */
    private long coolCount(CacheStrategy strategy) {
        long coolQps = Math.max(1L, longOf(strategy.coolQps(), 30L));
        if (isGlobal(strategy)) {
            long estimate = Math.max(1L, intOf(strategy.globalClientEstimate(), 1));
            long globalCool = longOf(strategy.globalCoolQps(), coolQps);
            long share = Math.max(1L, (globalCool + estimate - 1L) / estimate);
            coolQps = Math.min(coolQps, share);
        }
        return coolQps * Math.max(1L, windowSeconds(strategy));
    }

    private BoundedMap4j<String, AtomicInteger> coolStreak(CacheStrategy strategy) {
        return coolStreaks.computeIfAbsent(strategy.namespace(),
                s -> BoundedMap4j.fifo(capacityOf(strategy, strategy.maxHotKeys(), 1_000)));
    }

    private BoundedMap4j<String, Long> lastVerdicts(CacheStrategy strategy) {
        return lastVerdicts.computeIfAbsent(strategy.namespace(),
                s -> BoundedMap4j.fifo(capacityOf(strategy, strategy.maxHotKeys(), 1_000)));
    }

    private long windowSeconds(CacheStrategy strategy) {
        return Math.max(1L, longOf(strategy.hotWindow(), 5L));
    }

    private long windowMillis(CacheStrategy strategy) {
        return windowSeconds(strategy) * 1_000L;
    }

    private int windowSlices(CacheStrategy strategy) {
        return Math.max(2, intOf(strategy.hotWindowSlices(), 16));
    }

    private long cooldownMillis(CacheStrategy strategy) {
        return Math.max(0L, longOf(strategy.hotCooldown(), 30L)) * 1_000L;
    }

    private long syncIntervalMillis(CacheStrategy strategy) {
        return Math.max(100L, longOf(strategy.hotSyncInterval(), 1L) * 1_000L);
    }

    private static int capacityOf(CacheStrategy strategy, Supplier<Integer> source, int fallback) {
        long value = intOf(source, fallback);
        long ceiling = longOf(strategy.localMaxSize(), 1_000L);
        // 热 key 簿记的容量不该超过本地副本上限，否则簿记比缓存本身还大
        return (int) Math.max(1L, Math.min(value, Math.max(1L, ceiling)));
    }

    private static long longOf(Supplier<Long> supplier, long fallback) {
        if (supplier == null) {
            return fallback;
        }
        Long value = supplier.get();
        return value == null ? fallback : value;
    }

    private static Long longOrNull(Supplier<Long> supplier) {
        if (supplier == null) {
            return null;
        }
        return supplier.get();
    }

    private static int intOf(Supplier<Integer> supplier, int fallback) {
        if (supplier == null) {
            return fallback;
        }
        Integer value = supplier.get();
        return value == null ? fallback : value;
    }

    /**
     * 热 key 状态是否已就绪（供装配期校验；{@code GLOBAL} 必须有探测器）。
     *
     * @return 是否就绪
     */
    public final boolean hotKeyReady() {
        return centralCache4jHotKeyDetector != null;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{hotKey=" + (centralCache4jHotKeyDetector == null
                ? "LOCAL" : "GLOBAL") + '}';
    }
}
