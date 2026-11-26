package com.asialjim.cache4j.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.DynamicLocalCache;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.local.LocalStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热点准入判定：决定一个 key <strong>有没有资格</strong>进入本地缓存。
 *
 * <p>与存储完全解耦——它不实现 {@code LocalCache}，只回答三件事：
 * 这个 key 是否已热（{@link #accepts}）、这次访问是否让它升热（{@link #access}）、
 * 到点是否该上报（{@link #hotSync}）。</p>
 *
 * <p>两段式判定（理念：候选宁多勿少，升热必须精确）：</p>
 * <ol>
 *   <li><strong>候选预选</strong>：Count-Min Sketch 共享草图，固定内存、只会高估——
 *       在候选阶段这是安全方向（漏判热点会让流量全打到中央）；</li>
 *   <li><strong>升热精判</strong>：达到候选阈值的 key 才建 per-key 16 片滑动窗口，精确计数——
 *       误升热会挤掉真热点，所以这一阶段必须准。</li>
 * </ol>
 *
 * <p>因为不碰存储，同一份准入逻辑可以服务 HashMap 与 Caffeine 两种存储实现。</p>
 *
 * @author Asial Jim
 */
public final class HotKeyAdmission {

    private static final Logger LOG = LoggerFactory.getLogger(HotKeyAdmission.class);

    private final CacheStrategy strategy;
    private final HotKeyCoordinator coordinator;
    private final FrequencySketch sketch;
    private final LongSupplier nanoClock;

    /**
     * 清存储的回调：准入决定"这个 key 该从本地消失"，但真正删数据是存储层的事。
     *
     * <p>这样准入组件不依赖任何 {@code LocalCache} 实现，可同时服务 HashMap 与 Caffeine。</p>
     */
    private final java.util.function.Consumer<CacheKey> evict;

    private final ConcurrentHashMap<CacheKey, HotEntry> hotKeys = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CacheKey> lru = new ConcurrentLinkedQueue<>();
    private final List<Set<CacheKey>> hotByPartition;
    private final List<Map<CacheKey, Integer>> candidateByPartition;
    private final ConcurrentHashMap<CacheKey, LongAdder> pendingDelta = new ConcurrentHashMap<>();

    /**
     * 每 key 的精确滑动窗口（16 片）。只对"值得精确统计"的 key 建状态：
     * 由 CMS 粗筛（可能高估，宁多勿少）决定谁进来，容量上限 = {@code candidateCapacity}。
     */
    private final ConcurrentHashMap<CacheKey, HotWindow> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, Long> cooldownUntilNanos = new ConcurrentHashMap<>();

    private final AtomicLong accessCounter = new AtomicLong();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private volatile long nextSyncAtNanos;

    /**
     * @param strategy    策略（{@code localStrategy} 必须为 {@code DYNAMIC}）
     * @param coordinator 热 key 协调器；{@code coordination=GLOBAL} 时必填
     * @param nanoClock   单调时钟
     * @param evict       清存储的回调
     */
    public HotKeyAdmission(CacheStrategy strategy, HotKeyCoordinator coordinator, LongSupplier nanoClock,
                           java.util.function.Consumer<CacheKey> evict) {
        this.strategy = strategy;
        this.evict = evict;
        this.coordinator = coordinator;
        this.nanoClock = nanoClock;
        long now = nanoClock.getAsLong();
        this.sketch = new FrequencySketch(strategy.sketchWidth(), strategy.sketchDepth(),
                strategy.hotWindow().toNanos(), now, nanoClock);
        int partitions = strategy.hotReportPartitions();
        this.hotByPartition = new ArrayList<>(partitions);
        this.candidateByPartition = new ArrayList<>(partitions);
        for (int i = 0; i < partitions; i++) {
            this.hotByPartition.add(ConcurrentHashMap.newKeySet());
            this.candidateByPartition.add(new LinkedHashMap<>());
        }
    }

    public boolean accepts(CacheKey key) {
        return hotKeys.containsKey(key);
    }

    public boolean coordinationReady() {
        return strategy.coordination() != com.asialjim.cache4j.core.Coordination.GLOBAL || coordinator != null;
    }

    public boolean access(CacheKey key) {
        long now = nanoClock.getAsLong();
        sweepCooldownOccasionally(now);
        // ① CMS 粗筛：共享草图、固定内存、只会高估 —— 在候选阶段这是安全方向
        int approx = sketch.incrementAndEstimate(key, now);
        // ② 精判：值得统计的 key 走它自己的滑动窗口（精确），否则退回 CMS 估计
        long count = preciseCount(key, now, approx);

        HotEntry hot = hotKeys.get(key);
        if (hot != null) {
            hotKeys.put(key, hot.touch(now, saturate(count)));
            lru.offer(key);
            pendingDeltaFor(key).increment();
            return true;
        }

        boolean cooling = isCooling(key, now);
        if (cooling && count >= strategy.hotThresholdCount()) {
            // 冷却期：频次够了也不许升热（抑制"降热→立刻又升热"的抖动）
            LOG.debug("[{}]-读-[{}]-{}", "动态本地缓存", key.masked(), "冷却期内拒绝升热，窗口计数=" + count
                    + "，剩余冷却=" + cooldownRemainingMillis(key, now) + "ms");
        }
        if (!cooling && count >= strategy.hotThresholdCount()) {
            addDelta(key);
            promote(key, count, now);
            LOG.debug("[{}]-读-[{}]-{}", "动态本地缓存", key.masked(), "触发热升，窗口计数=" + count
                    + "（阈值=" + strategy.hotThresholdCount() + "），热点集合大小=" + hotKeys.size());
            return true;
        }

        long threshold = strategy.candidateCount();
        if (useCandidates() && count >= threshold) {
            upsertCandidate(partitionOf(key), key, saturate(count));
            pendingDeltaFor(key).increment();
        } else {
            removeCandidate(key);
        }
        return false;
    }

    /**
     * 取该 key 的窗口内精确计数。
     *
     * <p>窗口状态只为"可能成为热点"的 key 建立：用 CMS 的估计值（只会高估）做粗筛，
     * 达到候选阈值才建表，容量上限 {@code candidateCapacity}——这样长尾 key 只占共享草图的
     * 固定内存，不会撑爆 per-key 表。表满后退回 CMS 估计（宁可少算，也不无界增长）。</p>
     *
     * <p>建表时以"粗筛阈值"作为窗口初始计数：既保留建表前已攒的访问量（否则低速热点要
     * 再攒一整个窗口才升热），又不会把 CMS 的高估值直接灌进精确判定（否则一次高估就能误升热）。</p>
     *
     * @param key     key
     * @param now     当前纳秒
     * @param approx  CMS 估计值
     * @return 窗口内累计次数
     */
    private long preciseCount(CacheKey key, long now, int approx) {
        HotWindow window = windows.get(key);
        if (window != null) {
            return window.advanceAndIncrement(now);
        }
        if (approx < strategy.candidateCount()) {
            // 粗筛未过：不建状态
            return approx;
        }
        if (windows.size() >= strategy.candidateCapacity()) {
            return approx;
        }
        // 建表时用粗筛阈值做种：既不会丢掉"进入精确统计之前"已经攒下的访问量，
        // 也不会把 CMS 的高估值直接灌进来（否则一次高估就可能误升热）。
        // 注意 approx 已包含本次访问，而紧接着还会 advanceAndIncrement 一次，所以种子里要减去本次。
        int seed = saturate(Math.max(0L, Math.min(approx - 1L, strategy.candidateCount() - 1L)));
        HotWindow created = new HotWindow(strategy.hotWindowSlices(), strategy.hotWindow().toNanos(), now, seed);
        HotWindow previous = windows.putIfAbsent(key, created);
        return (previous != null ? previous : created).advanceAndIncrement(now);
    }

    public void hotSync(CacheKey key) {
        if (strategy.coordination() != com.asialjim.cache4j.core.Coordination.GLOBAL || coordinator == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        long next = nextSyncAtNanos;
        if (next == 0L) {
            nextSyncAtNanos = now + jitteredSyncInterval();
            return;
        }
        if (now - next < 0L) {
            return;
        }
        if (!syncing.compareAndSet(false, true)) {
            return;
        }
        Map<CacheKey, Long> batch = new LinkedHashMap<>();
        Map<CacheKey, Long> reported = null;
        try {
            int partition = partitionOf(key);
            for (CacheKey hotKey : hotByPartition.get(partition)) {
                batch.put(hotKey, takeDelta(hotKey));
            }
            for (CacheKey candidateKey : new ArrayList<>(candidateByPartition.get(partition).keySet())) {
                batch.putIfAbsent(candidateKey, takeDelta(candidateKey));
            }

            reported = selectBatch(batch);

            for (Map.Entry<CacheKey, Long> entry : reported.entrySet()) {
                if (entry.getValue() == 0L) {
                }
            }

            Map<CacheKey, HotKeyReport> reports = coordinator.reportAndStatusAll(strategy.namespace(), reported);
            applyCentralStatus(reports);
            nextSyncAtNanos = now + jitteredSyncInterval();
            final Map<CacheKey, Long> flushed = reported;
            LOG.debug("[{}]-{}-{}", "动态本地缓存", "热key", "namespace=" + strategy.namespace() + "，上报完成，分区=" + partitionOf(key)
                    + "，上报 key 数=" + flushed.size());
        } catch (RuntimeException e) {
            // 只回填真正上报过的增量（被倾斜截断的那部分已单独回填）
            if (reported != null) {
                for (Map.Entry<CacheKey, Long> entry : reported.entrySet()) {
                    restoreDelta(entry.getKey(), entry.getValue());
                }
            }
            long backoff = strategy.versionRetryBackoff() == null ? Duration.ofMillis(200).toNanos()
                    : strategy.versionRetryBackoff().toNanos();
            nextSyncAtNanos = now + backoff;
            LOG.warn("[{}]-{}-{}", "动态本地缓存", "热key", "热 key 上报失败，整批回填并退避，"
                    + "namespace=" + strategy.namespace() + "，退避=" + backoff + "ms 级，原因=" + e.getMessage());
        } finally {
            syncing.set(false);
        }
    }

    /**
     * 倾斜保护：超出硬上限时按 pending 增量取 top-N，其余回填到下一轮。
     *
     * @param batch 候选批次
     * @return 实际要上报的批次
     */
    private Map<CacheKey, Long> selectBatch(Map<CacheKey, Long> batch) {
        int limit = strategy.hotReportBatchMax();
        if (batch.size() <= limit) {
            return batch;
        }
        Map<CacheKey, Long> selected = topByDelta(batch, limit);
        for (Map.Entry<CacheKey, Long> entry : batch.entrySet()) {
            if (!selected.containsKey(entry.getKey())) {
                restoreDelta(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    private void promote(CacheKey key, long freq, long now) {
        while (hotKeys.size() >= strategy.maxHotKeys()) {
            CacheKey victim = pollLiveKeyFromLru();
            if (victim == null) {
                break;
            }
            if (hotKeys.remove(victim) != null) {
                hotByPartition.get(partitionOf(victim)).remove(victim);
                pendingDelta.remove(victim);
                evict.accept(victim);
            }
        }
        hotKeys.put(key, HotEntry.promoted(now, freq));
        hotByPartition.get(partitionOf(key)).add(key);
        lru.offer(key);
        removeCandidate(key);
    }

    /**
     * 降热：只由中央 {@code COOLING} 触发。
     *
     * @param key key
     * @param now 当前纳秒
     */
    private void demote(CacheKey key, long now) {
        if (hotKeys.remove(key) == null) {
            return;
        }
        hotByPartition.get(partitionOf(key)).remove(key);
        pendingDelta.remove(key);
        if (!strategy.hotCooldown().isZero()) {
            cooldownUntilNanos.put(key, now + strategy.hotCooldown().toNanos());
        }
        evict.accept(key);
        LOG.debug("[{}]-{}-{}", "动态本地缓存", "热key", "降热，来源=中央 COOLING，key=" + key.masked()
                + "，进入冷却=" + strategy.hotCooldown() + "，热点集合大小=" + hotKeys.size());
    }

    /**
     * @param key key
     * @param now 当前纳秒
     * @return 该 key 的本地窗口计数（无窗口时为 0）
     */
    private long localCount(CacheKey key, long now) {
        HotWindow window = windows.get(key);
        return window == null ? 0L : window.total(now);
    }

    private void applyCentralStatus(Map<CacheKey, HotKeyReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        long now = nanoClock.getAsLong();
        reports.forEach((key, report) -> {
            HotKeyStatus status = report == null ? HotKeyStatus.UNKNOWN : report.status();
            switch (status) {
                case HOT -> {
                    if (!hotKeys.containsKey(key) && !isCooling(key, now)) {
                        // 中央只回传状态，本地记录自己的窗口计数（不回灌中央数值）
                        promote(key, localCount(key, now), now);
                        LOG.debug("[{}]-{}-{}", "动态本地缓存", "热key", "升热，来源=中央 HOT，key="
                                + key.masked() + "，本地窗口计数=" + localCount(key, now)
                                + "，热点集合大小=" + hotKeys.size());
                    }
                }
                case COOLING -> {
                    if (hotKeys.containsKey(key)) {
                        demote(key, now);
                    }
                }
                default -> {
                    // ACTIVE / UNKNOWN：保持现状，不降热
                }
            }
        });
    }

    private void forget(CacheKey key) {
        if (hotKeys.remove(key) != null) {
            hotByPartition.get(partitionOf(key)).remove(key);
        }
        removeCandidate(key);
        pendingDelta.remove(key);
        cooldownUntilNanos.remove(key);
    }

    private CacheKey pollLiveKeyFromLru() {
        CacheKey candidate;
        while ((candidate = lru.poll()) != null) {
            if (hotKeys.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 候选桶

    private boolean useCandidates() {
        return strategy.coordination() == com.asialjim.cache4j.core.Coordination.GLOBAL;
    }

    private void upsertCandidate(int partition, CacheKey key, int freq) {
        Map<CacheKey, Integer> bucket = candidateByPartition.get(partition);
        synchronized (bucket) {
            bucket.remove(key);
            bucket.put(key, freq);
            int capacity = Math.max(1, strategy.candidateCapacity() / strategy.hotReportPartitions());
            while (bucket.size() > capacity) {
                // 按估计值保留 top-B，不用 LRU/FIFO：预选的意义是"未来可能热"
                CacheKey coldest = null;
                int min = Integer.MAX_VALUE;
                for (Map.Entry<CacheKey, Integer> entry : bucket.entrySet()) {
                    if (entry.getValue() < min) {
                        min = entry.getValue();
                        coldest = entry.getKey();
                    }
                }
                if (coldest == null) {
                    break;
                }
                bucket.remove(coldest);
                pendingDelta.remove(coldest);
            }
        }
    }

    private void removeCandidate(CacheKey key) {
        Map<CacheKey, Integer> bucket = candidateByPartition.get(partitionOf(key));
        synchronized (bucket) {
            bucket.remove(key);
        }
    }

    // ------------------------------------------------------------------ 增量缓冲

    private LongAdder pendingDeltaFor(CacheKey key) {
        LongAdder adder = pendingDelta.get(key);
        if (adder != null) {
            return adder;
        }
        int bound = strategy.maxHotKeys() + strategy.candidateCapacity();
        if (pendingDelta.size() >= bound) {
            return new LongAdder();
        }
        return pendingDelta.computeIfAbsent(key, ignored -> new LongAdder());
    }

    private void addDelta(CacheKey key) {
        pendingDeltaFor(key).increment();
    }

    private long takeDelta(CacheKey key) {
        LongAdder adder = pendingDelta.remove(key);
        return adder == null ? 0L : adder.sumThenReset();
    }

    private void restoreDelta(CacheKey key, long delta) {
        if (delta != 0L) {
            pendingDelta.computeIfAbsent(key, ignored -> new LongAdder()).add(delta);
        }
    }

    private Map<CacheKey, Long> topByDelta(Map<CacheKey, Long> batch, int limit) {
        List<Map.Entry<CacheKey, Long>> entries = new ArrayList<>(batch.entrySet());
        entries.sort(Map.Entry.<CacheKey, Long>comparingByValue(Comparator.reverseOrder()));
        Map<CacheKey, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            result.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return result;
    }

    // ------------------------------------------------------------------ 冷却

    /**
     * @param key key
     * @param now 当前纳秒
     * @return 剩余冷却毫秒数；不在冷却期返回 0
     */
    private long cooldownRemainingMillis(CacheKey key, long now) {
        Long until = cooldownUntilNanos.get(key);
        return until == null ? 0L : Math.max(0L, (until - now) / 1_000_000L);
    }

    private boolean isCooling(CacheKey key, long now) {
        Long until = cooldownUntilNanos.get(key);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            cooldownUntilNanos.remove(key, until);
            return false;
        }
        return true;
    }

    private void sweepCooldownOccasionally(long now) {
        if ((accessCounter.incrementAndGet() & 0x3FFL) != 0L) {
            return;
        }
        for (Map.Entry<CacheKey, Long> entry : cooldownUntilNanos.entrySet()) {
            if (entry.getValue() <= now) {
                cooldownUntilNanos.remove(entry.getKey(), entry.getValue());
                return;
            }
        }
        // 窗口状态清理：整窗无访问（累计归零）的 key 移出表，避免"曾经有希望"的 key 长期占槽
        windows.entrySet().removeIf(entry -> entry.getValue().total(now) == 0L);
    }

    // ------------------------------------------------------------------ 分区与时钟

    private int partitionOf(CacheKey key) {
        return com.asialjim.cache4j.core.HashPartitions.of(key, strategy.hotReportPartitions());
    }

    private long jitteredSyncInterval() {
        return strategy.hotSyncInterval().toNanos();
    }

    private static Duration min(Duration... values) {
        Duration result = null;
        for (Duration value : values) {
            if (value == null || value.isZero()) {
                continue;
            }
            if (result == null || value.compareTo(result) < 0) {
                result = value;
            }
        }
        return result;
    }

    /**
     * 热点条目。
     *
     * @param promotedAtNanos  升热时刻
     * @param lastAccessNanos  最近访问时刻
     * @param lastSyncNanos    最近一次上报时刻
     * @param lastCentralStatus 最近一次中央返回的状态（仅观测）
     * @param lastEstimate     最近一次估计频次
     */
    private record HotEntry(
            long promotedAtNanos,
            long lastAccessNanos,
            long lastSyncNanos,
            HotKeyStatus lastCentralStatus,
            int lastEstimate
    ) {

        static HotEntry promoted(long now, long estimate) {
            return new HotEntry(now, now, 0L, HotKeyStatus.UNKNOWN,
                    saturate(estimate));
        }

        HotEntry touch(long now, int estimate) {
            return new HotEntry(promotedAtNanos, now, lastSyncNanos, lastCentralStatus, estimate);
        }
    }

    /**
     * long → int 的饱和转换（窗口计数理论上可能超过 int，实际不会）。
     *
     * @param value 原始值
     * @return 饱和后的 int
     */
    private static int saturate(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return value < 0L ? 0 : (int) value;
    }

    /**
     * @return 当前热点 key 集合（观测用）
     */
    public Set<CacheKey> hotKeySet() {
        return new LinkedHashSet<>(hotKeys.keySet());
    }

    /**
     * 清掉已过期的冷却记录（{@code maintain} 时调用；准入侧状态，与存储无关）。
     */
    public void sweepCooldown() {
        sweepCooldownOccasionally(nanoClock.getAsLong());
    }

    /**
     * 清空全部准入状态（{@code clear()} 时调用）。
     */
    public void clear() {
        hotKeys.clear();
        hotByPartition.forEach(Set::clear);
        candidateByPartition.forEach(Map::clear);
        pendingDelta.clear();
        cooldownUntilNanos.clear();
        windows.clear();
        lru.clear();
    }
}
