package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
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

/**
 * {@code DYNAMIC} 策略：本地只缓存<strong>读频次</strong>达标的 key。
 *
 * <h2>四条规则</h2>
 * <ol>
 *   <li>本地升热立即生效：本地窗口频次达到 {@code localHotThreshold} 直接升热，不等中央；</li>
 *   <li>本地不升热 ≠ 不热：到点的热同步事件里按<strong>分区</strong>批量上报，由中央裁决；</li>
 *   <li>降热只认中央 {@code COOLING}；</li>
 *   <li>预选阈值 {@code U} 严格小于 {@code localHotThreshold}（派生值，见
 *       {@link LocalCacheSpec.DynamicSpec#candidateThreshold()}）。</li>
 * </ol>
 *
 * <p><strong>频次只统计读</strong>：{@code put} / {@code evict} / {@code clear} / 对账 / 上报都不计频。
 * 因为 hotkey 的唯一目的是减少对 L2 的读，写必须穿透中央、本地有无副本都不影响 L2 写压力，
 * 所以写在这套机制里没有可优化的对象。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class DynamicLocalCache<V> implements LocalCache<V> {

    private final LocalCache<V> delegate;
    private final LocalCacheSpec spec;
    private final LocalCacheSpec.DynamicSpec dynamic;
    private final HotKeyCoordinator coordinator;
    private final FrequencySketch sketch;
    private final LongSupplier nanoClock;
    private final CacheCounters counters = new CacheCounters();

    private final ConcurrentHashMap<CacheKey, HotEntry> hotKeys = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CacheKey> lru = new ConcurrentLinkedQueue<>();
    private final List<Set<CacheKey>> hotByPartition;
    private final List<Map<CacheKey, Integer>> candidateByPartition;
    private final ConcurrentHashMap<CacheKey, LongAdder> pendingDelta = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, Long> cooldownUntilNanos = new ConcurrentHashMap<>();

    private final AtomicLong accessCounter = new AtomicLong();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private volatile long nextSyncAtNanos;

    /**
     * @param delegate    存储层（HashMap / Caffeine），其 {@code spec} 与本对象一致
     * @param spec        配置（策略必须为 {@code DYNAMIC}）
     * @param coordinator 热 key 协调器；{@code coordination=GLOBAL} 时必填
     * @param nanoClock   单调时钟
     */
    public DynamicLocalCache(LocalCache<V> delegate, LocalCacheSpec spec,
                             HotKeyCoordinator coordinator, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.spec = spec;
        this.dynamic = spec.dynamic();
        this.coordinator = coordinator;
        this.nanoClock = nanoClock;
        long now = nanoClock.getAsLong();
        this.sketch = new FrequencySketch(dynamic.sketchWidth(), dynamic.sketchDepth(),
                dynamic.window().toNanos(), now, nanoClock);
        int partitions = dynamic.hotReportPartitions();
        this.hotByPartition = new ArrayList<>(partitions);
        this.candidateByPartition = new ArrayList<>(partitions);
        for (int i = 0; i < partitions; i++) {
            this.hotByPartition.add(ConcurrentHashMap.newKeySet());
            this.candidateByPartition.add(new LinkedHashMap<>());
        }
    }

    // ------------------------------------------------------------------ LocalCache

    @Override
    public LocalStrategy strategy() {
        return LocalStrategy.DYNAMIC;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public boolean coordinationReady() {
        return dynamic.coordination() != LocalCacheSpec.Coordination.GLOBAL || coordinator != null;
    }

    @Override
    public Set<CacheKey> keys() {
        return delegate.keys();
    }

    @Override
    public boolean access(CacheKey key) {
        long now = nanoClock.getAsLong();
        sweepCooldownOccasionally(now);
        int freq = sketch.incrementAndEstimate(key, now);

        HotEntry hot = hotKeys.get(key);
        if (hot != null) {
            hotKeys.put(key, hot.touch(now, freq));
            lru.offer(key);
            pendingDeltaFor(key).increment();
            return true;
        }

        if (!isCooling(key, now) && freq >= dynamic.localHotThreshold()) {
            addDelta(key);
            promote(key, freq, now);
            counters.increment(CacheCounters.Counter.PROMOTION);
            counters.increment(CacheCounters.Counter.LOCAL_PROMOTION);
            return true;
        }

        long threshold = dynamic.candidateThreshold();
        if (useCandidates() && freq >= threshold) {
            upsertCandidate(partitionOf(key), key, freq);
            pendingDeltaFor(key).increment();
        } else {
            removeCandidate(key);
        }
        return false;
    }

    @Override
    public void hotSync(CacheKey key) {
        if (dynamic.coordination() != LocalCacheSpec.Coordination.GLOBAL || coordinator == null) {
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
                    counters.increment(CacheCounters.Counter.HOT_COMPENSATION_QUERY);
                }
            }

            Map<CacheKey, HotKeyReport> reports = coordinator.reportAndStatusAll(spec.namespace(), reported);
            applyCentralStatus(reports);
            nextSyncAtNanos = now + jitteredSyncInterval();
            counters.increment(CacheCounters.Counter.HOT_SYNC);
        } catch (RuntimeException e) {
            // 只回填真正上报过的增量（被倾斜截断的那部分已单独回填）
            if (reported != null) {
                for (Map.Entry<CacheKey, Long> entry : reported.entrySet()) {
                    restoreDelta(entry.getKey(), entry.getValue());
                }
            }
            long backoff = spec.version() == null ? Duration.ofMillis(200).toNanos()
                    : spec.version().retryBackoff().toNanos();
            nextSyncAtNanos = now + backoff;
            counters.increment(CacheCounters.Counter.HOT_SYNC_ERROR);
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
        int limit = dynamic.hotReportBatchMax();
        if (batch.size() <= limit) {
            return batch;
        }
        Map<CacheKey, Long> selected = topByDelta(batch, limit);
        for (Map.Entry<CacheKey, Long> entry : batch.entrySet()) {
            if (!selected.containsKey(entry.getKey())) {
                restoreDelta(entry.getKey(), entry.getValue());
                counters.increment(CacheCounters.Counter.HOT_REPORT_DROPPED);
            }
        }
        return selected;
    }

    @Override
    public Lookup<LocalEntry<V>> lookup(CacheKey key) {
        return delegate.lookup(key);
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return delegate.containsKey(key);
    }

    @Override
    public void put(CacheKey key, LocalEntry<V> value, Duration ttl) {
        if (!hotKeys.containsKey(key)) {
            // 冷 key 不落本地：写不计频次，也就不可能因为它"热"。需要 read-your-write 请用 STATIC。
            counters.increment(CacheCounters.Counter.DYNAMIC_PUT_DISCARDED);
            return;
        }
        Duration effective = min(ttl, dynamic.hotTtl());
        delegate.put(key, value, effective);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, LocalEntry<V> value, Duration ttl) {
        if (!hotKeys.containsKey(key)) {
            counters.increment(CacheCounters.Counter.DYNAMIC_PUT_DISCARDED);
            return false;
        }
        return delegate.putIfAbsent(key, value, min(ttl, dynamic.hotTtl()));
    }

    @Override
    public void evict(CacheKey key) {
        forget(key);
        delegate.evict(key);
    }

    @Override
    public void clear() {
        hotKeys.clear();
        hotByPartition.forEach(Set::clear);
        candidateByPartition.forEach(Map::clear);
        pendingDelta.clear();
        cooldownUntilNanos.clear();
        lru.clear();
        delegate.clear();
    }

    @Override
    public void maintain() {
        delegate.maintain();
        long now = nanoClock.getAsLong();
        cooldownUntilNanos.forEach((key, until) -> {
            if (until <= now) {
                cooldownUntilNanos.remove(key, until);
            }
        });
    }

    @Override
    public CacheStats stats() {
        counters.setEstimatedSize(delegate.stats().estimatedSize());
        counters.setHotKeyCount(hotKeys.size());
        long candidates = 0L;
        for (Map<CacheKey, Integer> bucket : candidateByPartition) {
            candidates += bucket.size();
        }
        counters.setCandidateCount(candidates);
        return counters;
    }

    @Override
    public void close() {
        clear();
        delegate.close();
    }

    // ------------------------------------------------------------------ 升 / 降热

    /**
     * 本地升热（容量满时按"最久未访问"淘汰，属容量管理而非语义降热）。
     *
     * @param key  key
     * @param freq 触发时的估计频次
     * @param now  当前纳秒
     */
    private void promote(CacheKey key, long freq, long now) {
        while (hotKeys.size() >= dynamic.maxHotKeys()) {
            CacheKey victim = pollLiveKeyFromLru();
            if (victim == null) {
                break;
            }
            if (hotKeys.remove(victim) != null) {
                hotByPartition.get(partitionOf(victim)).remove(victim);
                pendingDelta.remove(victim);
                delegate.evict(victim);
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
        if (!dynamic.cooldown().isZero()) {
            cooldownUntilNanos.put(key, now + dynamic.cooldown().toNanos());
        }
        delegate.evict(key);
        counters.increment(CacheCounters.Counter.DEMOTION);
        counters.increment(CacheCounters.Counter.GLOBAL_DEMOTION);
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
                        promote(key, report.windowCount(), now);
                        counters.increment(CacheCounters.Counter.PROMOTION);
                        counters.increment(CacheCounters.Counter.GLOBAL_PROMOTION);
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
        return dynamic.coordination() == LocalCacheSpec.Coordination.GLOBAL;
    }

    private void upsertCandidate(int partition, CacheKey key, int freq) {
        Map<CacheKey, Integer> bucket = candidateByPartition.get(partition);
        synchronized (bucket) {
            bucket.remove(key);
            bucket.put(key, freq);
            int capacity = Math.max(1, dynamic.candidateCapacity() / dynamic.hotReportPartitions());
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
        int bound = dynamic.maxHotKeys() + dynamic.candidateCapacity();
        if (pendingDelta.size() >= bound) {
            counters.increment(CacheCounters.Counter.HOT_REPORT_DROPPED);
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
    }

    // ------------------------------------------------------------------ 分区与时钟

    private int partitionOf(CacheKey key) {
        int hash = key.logical().hashCode();
        int spread = hash ^ (hash >>> 16);
        return spread & (dynamic.hotReportPartitions() - 1);
    }

    private long jitteredSyncInterval() {
        return dynamic.syncInterval().toNanos();
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
            return new HotEntry(now, now, 0L, HotKeyStatus.UNKNOWN, (int) Math.min(Integer.MAX_VALUE, estimate));
        }

        HotEntry touch(long now, int estimate) {
            return new HotEntry(promotedAtNanos, now, lastSyncNanos, lastCentralStatus, estimate);
        }
    }

    /**
     * @return 当前热点 key 集合（观测用）
     */
    public Set<CacheKey> hotKeySet() {
        return new LinkedHashSet<>(hotKeys.keySet());
    }
}
