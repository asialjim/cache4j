package com.asialjim.cache4j.core.twolevel;

import com.asialjim.cache4j.core.Cache;
import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.HashPartitions;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.CacheStats;
import com.asialjim.cache4j.core.CacheStatsSnapshot;
import com.asialjim.cache4j.core.KeyEncoder;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.central.VersionedLookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.log.CacheLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 默认二级缓存编排实现。
 *
 * <h2>读路径</h2>
 * <pre>{@code
 * access 事件（DYNAMIC 记频 + 热 key 同步）
 *   → 命名空间级对账信号量（到点则批量 HMGET 版本，清除不一致副本）
 *   → 本地命中（区分真值 / 已确认不存在）
 *   → 中央合并 I/O（版本 + 值）→ 回填
 * }</pre>
 *
 * <h2>记账锚点</h2>
 * <p>{@code requestCount} 只在业务入口记<strong>一次</strong>：单飞的 double-check 重查走内部
 * 重入路径，不再计数；对账、热 key 上报、写与失效也都不计入。</p>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class DefaultTwoLevelCache<K, V> implements TwoLevelCache<K, V> {

    private static final CacheLogger LOG = CacheLogger.get(DefaultTwoLevelCache.class);

    private final String namespace;
    private final KeyEncoder keyEncoder;
    private final LocalCache<V> local;
    private final CentralCache<V> central;
    private final LocalCacheSpec localSpec;
    private final CentralCacheSpec centralSpec;
    private final CacheCounters counters;
    private final LongSupplier nanoClock;
    private final LongSupplier milliClock;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<Lookup<V>>> inflight = new ConcurrentHashMap<>();

    /**
     * 命名空间级对账信号量：下一次对账时间点（纳秒）；0 表示尚未初始化。
     */
    private volatile long nextCheckAtNanos;

    /**
     * 同一命名空间同一时刻只允许一个线程对账。
     */
    private final AtomicBoolean reconciling = new AtomicBoolean();

    private DefaultTwoLevelCache(Builder<K, V> builder) {
        this.namespace = builder.namespace;
        this.keyEncoder = builder.keyEncoder;
        this.local = builder.local;
        this.central = builder.central;
        this.localSpec = local.spec();
        this.centralSpec = central.spec();
        this.counters = builder.statsEnabled ? new CacheCounters() : null;
        this.nanoClock = builder.nanoClock;
        this.milliClock = builder.milliClock;

        LOG.debug(CacheLogger.Subject.TWO_LEVEL, "装配", () -> "namespace=" + namespace
                + "，strategy=" + localSpec.strategy()
                + "，maxSize=" + localSpec.maxSize()
                + "，localTtl=" + localSpec.ttl()
                + "，centralTtl=" + centralSpec.ttl()
                + "，nullTtl=" + centralSpec.nullTtl()
                + "，failOpen=" + centralSpec.failOpen()
                + "，statsEnabled=" + builder.statsEnabled);
    }

    // ------------------------------------------------------------------ Cache

    @Override
    public String name() {
        return namespace;
    }

    @Override
    public Lookup<V> lookup(K key) {
        CacheKey cacheKey = encode(key);
        count(CacheCounters.Counter.REQUEST);
        ReadResult<V> result = read(cacheKey);
        countOutcome(result);
        return result.lookup();
    }

    @Override
    public Lookup<V> lookup(K key, CacheLoader<K, V> loader) {
        CacheKey cacheKey = encode(key);
        count(CacheCounters.Counter.REQUEST);

        ReadResult<V> first = read(cacheKey);
        if (first.source() != Source.NONE) {
            countOutcome(first);
            return first.lookup();
        }
        // 需要回源：本次请求计一次 miss（即使后面 double-check 命中，结论仍是"需要回源"）
        count(CacheCounters.Counter.MISS);

        CompletableFuture<Lookup<V>> mine = new CompletableFuture<>();
        CompletableFuture<Lookup<V>> existing = inflight.putIfAbsent(cacheKey, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            ReadResult<V> again = read(cacheKey);
            if (again.source() != Source.NONE) {
                mine.complete(again.lookup());
                return again.lookup();
            }
            V value;
            count(CacheCounters.Counter.SOURCE_LOAD);
            try {
                value = loader.load(key);
            } catch (RuntimeException e) {
                count(CacheCounters.Counter.LOAD_FAILURE);
                LOG.warnRead(CacheLogger.Subject.TWO_LEVEL, cacheKey, () -> "loader 执行失败，异常原样抛出，原因="
                        + e.getClass().getSimpleName() + ":" + e.getMessage());
                mine.completeExceptionally(e);
                throw e;
            }
            Lookup<V> loaded;
            if (value == null) {
                count(CacheCounters.Counter.SOURCE_NULL);
                loaded = writeNegative(cacheKey);
            } else {
                loaded = writeValue(cacheKey, value, null);
            }
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(cacheKey, mine);
        }
    }

    @Override
    public boolean containsKey(K key) {
        return !lookup(key).isMiss();
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        CacheKey cacheKey = encode(key);
        if (value == null) {
            writeNegativeOrEvict(cacheKey, ttl);
            return;
        }
        writeValue(cacheKey, value, ttl);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        CacheKey cacheKey = encode(key);
        Duration effective = ttl != null ? ttl : centralSpec.ttl();
        try {
            Optional<Long> revision = central.putIfAbsentAndGetRevision(cacheKey, value, effective)
                    .stream().boxed().findFirst();
            if (revision.isEmpty()) {
                return false;
            }
            count(CacheCounters.Counter.PUT);
            putLocal(cacheKey, LocalEntry.hit(value, revision.get()), effective, "写中央并写本地", true);
            return true;
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (centralSpec.failOpen()) {
                // 无法保证原子性，不能静默当成"本地成功"
                return false;
            }
            throw e;
        }
    }

    @Override
    public void evict(K key) {
        CacheKey cacheKey = encode(key);
        try {
            central.evict(cacheKey);
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warnThrottled(CacheLogger.Subject.TWO_LEVEL, "失效", () -> "中央失效失败，降级清理本地，key="
                    + cacheKey.masked() + "，原因=" + e.getMessage());
        } finally {
            local.evict(cacheKey);
            count(CacheCounters.Counter.EVICT);
        }
    }

    @Override
    public void clear() {
        try {
            central.clear();
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warnThrottled(CacheLogger.Subject.TWO_LEVEL, "清空", () -> "中央清空失败，降级清理本地，"
                    + "namespace=" + namespace + "，原因=" + e.getMessage());
        } finally {
            local.clear();
            count(CacheCounters.Counter.CLEAR);
        }
    }

    @Override
    public CacheStats stats() {
        if (counters == null) {
            return CacheStats.noop();
        }
        // 审计层与版本/诊断取门面自己的计数；热 key 控制层取本地装饰器（唯一来源，避免双计）
        CacheStats localStats = local.stats();
        return new CacheStatsSnapshot(
                counters.requestCount(),
                counters.localHitCount(),
                counters.localNegativeHitCount(),
                counters.centralHitCount(),
                counters.centralNegativeHitCount(),
                counters.missCount(),
                counters.sourceLoadCount(),
                counters.sourceNullCount(),
                counters.loadFailureCount(),
                counters.putCount(),
                counters.evictCount(),
                counters.clearCount(),
                localStats.promotionCount(),
                localStats.demotionCount(),
                localStats.localPromotionCount(),
                localStats.globalPromotionCount(),
                localStats.globalDemotionCount(),
                localStats.dynamicPutDiscarded(),
                localStats.hotKeyCount(),
                localStats.candidateCount(),
                localStats.hotSyncCount(),
                localStats.hotSyncErrorCount(),
                localStats.hotReportDropped(),
                localStats.hotCompensationQueryCount(),
                counters.versionSyncCount(),
                counters.versionSyncErrorCount(),
                counters.versionInvalidationCount(),
                counters.deadFieldDropped(),
                counters.backfillDiscardedCount(),
                counters.storeHitCount(),
                counters.storeMissCount(),
                counters.commandCount(),
                counters.centralErrorCount(),
                counters.codecErrorCount(),
                local.available() ? localStats.estimatedSize() : -1L);
    }

    @Override
    public void close() {
        LOG.debug(CacheLogger.Subject.TWO_LEVEL, "关闭", "namespace=" + namespace + "，关闭门面，取消进行中的回源");
        inflight.forEach((key, future) ->
                future.completeExceptionally(new CacheException(CacheResCode.UNKNOWN, "缓存已关闭")));
        inflight.clear();
        // 只关本地；中央连接可能被多个缓存共享，由 provider 负责
        local.close();
    }

    @Override
    public LocalStrategy strategy() {
        return localSpec.strategy();
    }

    @Override
    public LocalCache<V> local() {
        return local;
    }

    @Override
    public CentralCache<V> central() {
        return central;
    }

    // ------------------------------------------------------------------ 读路径

    private enum Source {
        LOCAL,
        CENTRAL,
        NONE
    }

    private record ReadResult<V>(Lookup<V> lookup, Source source) {
    }

    private ReadResult<V> read(CacheKey cacheKey) {
        long now = nanoClock.getAsLong();
        String localSubject = CacheLogger.Subject.localOf(localSpec.strategy());
        boolean promoted = false;

        if (local.available()) {
            boolean hotBefore = local.accepts(cacheKey);
            local.access(cacheKey);              // DYNAMIC 可能在此触发升热（装饰器自己记日志）
            promoted = !hotBefore && local.accepts(cacheKey);
            local.hotSync(cacheKey);
            maybeReconcile(cacheKey, now);      // 用当前 key 选对账分区

            LocalEntry<V> entry = local.entry(cacheKey);
            if (entry != null) {
                LOG.read(localSubject, cacheKey, detailHit(hotBefore || promoted));
                return entry.isNegative()
                        ? new ReadResult<>(Lookup.negative(), Source.LOCAL)
                        : new ReadResult<>(Lookup.hit(entry.value()), Source.LOCAL);
            }
            if (!promoted) {
                // 本次访问触发升热时不记 miss 行：装饰器已记「触发热升」，回填时会记「热升回填到本地」
                LOG.read(localSubject, cacheKey, detailMiss(hotBefore));
            }
        } else {
            LOG.read(CacheLogger.Subject.TWO_LEVEL, cacheKey, "本地策略=NONE，硬读中央缓存");
        }

        VersionedLookup<V> centralLookup = centralLookup(cacheKey);
        if (centralLookup == null) {
            return new ReadResult<>(Lookup.miss(), Source.NONE);
        }
        if (centralLookup.lookup().isHit()) {
            backfill(cacheKey, LocalEntry.hit(centralLookup.lookup().optional().orElseThrow(),
                    centralLookup.revision()), promoted);
            return new ReadResult<>(centralLookup.lookup(), Source.CENTRAL);
        }
        if (centralLookup.lookup().isNegative()) {
            if (centralSpec.nullCachingEnabled()) {
                backfill(cacheKey, LocalEntry.negative(centralLookup.revision()), promoted);
            } else {
                local.evict(cacheKey);
            }
            return new ReadResult<>(Lookup.negative(), Source.CENTRAL);
        }
        local.evict(cacheKey);
        return new ReadResult<>(Lookup.miss(), Source.NONE);
    }

    /**
     * 本地命中时的详情文案。
     *
     * @param hot 是否热点（DYNAMIC）
     * @return 文案
     */
    private String detailHit(boolean hot) {
        return localSpec.strategy() == LocalStrategy.DYNAMIC ? "热key，命中" : "命中";
    }

    /**
     * 本地未命中时的详情文案。
     *
     * @param hot 是否热点（DYNAMIC）
     * @return 文案
     */
    private String detailMiss(boolean hot) {
        return localSpec.strategy() == LocalStrategy.DYNAMIC
                ? (hot ? "热key，miss，硬读中央缓存" : "冷key，miss，硬读中央缓存")
                : "miss，硬读中央缓存";
    }

    private VersionedLookup<V> centralLookup(CacheKey cacheKey) {
        try {
            return central.lookupWithRevision(cacheKey);
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warnRead(CacheLogger.Subject.TWO_LEVEL, cacheKey,
                    () -> "中央读取失败，按未命中降级，原因="
                            + e.getClass().getSimpleName() + ":" + e.getMessage());
            return null;
        }
    }

    private void countOutcome(ReadResult<V> result) {
        if (result.source() == Source.LOCAL) {
            count(result.lookup().isNegative()
                    ? CacheCounters.Counter.LOCAL_NEGATIVE_HIT
                    : CacheCounters.Counter.LOCAL_HIT);
            return;
        }
        if (result.source() == Source.CENTRAL) {
            count(result.lookup().isNegative()
                    ? CacheCounters.Counter.CENTRAL_NEGATIVE_HIT
                    : CacheCounters.Counter.CENTRAL_HIT);
            return;
        }
        count(CacheCounters.Counter.MISS);
    }

    /**
     * 回填：只有现有副本的 revision 不大于本次读到的 revision 时才覆盖。
     */
    private void backfill(CacheKey cacheKey, LocalEntry<V> entry, boolean promoted) {
        if (!local.available()) {
            return;
        }
        LocalEntry<V> current = local.entry(cacheKey);
        if (current != null && current.revision() > entry.revision()) {
            count(CacheCounters.Counter.BACKFILL_DISCARDED);
            LOG.read(CacheLogger.Subject.TWO_LEVEL, cacheKey, () -> "本地已有更新版本，丢弃本次回填，读到的版本="
                    + entry.revision() + "，本地版本=" + current.revision());
            return;
        }
        putLocal(cacheKey, entry, entry.isNegative() ? centralSpec.nullTtl() : null,
                promoted ? "热升回填到本地" : "回填到本地");
    }

    /**
     * 写本地（调用方写入与内部回填共用）。
     *
     * <p>日志语义靠 {@code process} 区分：调用方写入记 {@code 写}，读路径回填记 {@code 回填} ——
     * 两者都会让"冷 key 不落本地"，但这是两件不同的事，不能都写成"写"。</p>
     *
     * @param cacheKey   key
     * @param entry      条目
     * @param effective  生效 TTL（可空）
     * @param detail     详细信息
     */
    private void putLocal(CacheKey cacheKey, LocalEntry<V> entry, Duration effective, String detail) {
        putLocal(cacheKey, entry, effective, detail, false);
    }

    /**
     * 写本地。
     *
     * @param cacheKey  key
     * @param entry     条目
     * @param effective 生效 TTL（可空）
     * @param detail    详情文案
     * @param writeOp   {@code true} 表示调用方写入（日志用"写"），{@code false} 表示内部回填（日志用"读"）
     */
    private void putLocal(CacheKey cacheKey, LocalEntry<V> entry, Duration effective,
                          String detail, boolean writeOp) {
        if (!local.available()) {
            if (writeOp) {
                LOG.write(CacheLogger.Subject.TWO_LEVEL, cacheKey, "本地策略=NONE，仅写中央");
            }
            return;
        }
        Duration localTtl = localSpec.ttl();
        Duration chosen = effective != null && localTtl != null && effective.compareTo(localTtl) < 0
                ? effective : localTtl;

        boolean accepted = local.accepts(cacheKey);
        local.put(cacheKey, entry, chosen);          // 是否真的写入由本地实现决定（DYNAMIC 会拒绝冷 key）
        String subject = CacheLogger.Subject.localOf(localSpec.strategy());

        if (accepted) {
            if (writeOp) {
                LOG.write(subject, cacheKey, detail);
            } else {
                LOG.read(subject, cacheKey, detail);
            }
        } else if (writeOp) {
            // 调用方写入被本地拒绝：这是"写"这条业务操作的结果
            LOG.write(subject, cacheKey, "冷key，不落本地，仅写中央");
        }
        // 回填被拒绝时不额外记：前面那条 "冷key，miss，硬读中央缓存" 已经说明了
    }

    // ------------------------------------------------------------------ 写路径

    private Lookup<V> writeValue(CacheKey cacheKey, V value, Duration ttl) {
        Duration effective = ttl != null ? ttl : centralSpec.ttl();
        long revision;
        try {
            revision = central.putAndGetRevision(cacheKey, value, effective);
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            // 默认不写本地：避免"只有我能看到的新值"
            count(CacheCounters.Counter.PUT);
            LOG.warnWrite(CacheLogger.Subject.TWO_LEVEL, cacheKey, () -> "中央写失败，按 failOpen 降级（不写本地），"
                    + "原因=" + e.getClass().getSimpleName() + ":" + e.getMessage());
            return Lookup.hit(value);
        }
        count(CacheCounters.Counter.PUT);
        putLocal(cacheKey, LocalEntry.hit(value, revision), effective, "写中央并写本地", true);
        return Lookup.hit(value);
    }

    private Lookup<V> writeNegative(CacheKey cacheKey) {
        if (!centralSpec.nullCachingEnabled()) {
            return Lookup.miss();
        }
        try {
            long revision = central.putNegativeAndGetRevision(cacheKey, centralSpec.nullTtl());
            putLocal(cacheKey, LocalEntry.negative(revision), centralSpec.nullTtl(), "写中央并写本地（空值）", true);
            return Lookup.negative();
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            return Lookup.miss();
        }
    }

    private void writeNegativeOrEvict(CacheKey cacheKey, Duration ttl) {
        if (centralSpec.nullCachingEnabled()) {
            Duration effective = ttl != null ? ttl : centralSpec.nullTtl();
            try {
                long revision = central.putNegativeAndGetRevision(cacheKey, effective);
                count(CacheCounters.Counter.PUT);
                putLocal(cacheKey, LocalEntry.negative(revision), effective, "写中央并写本地（空值）", true);
                return;
            } catch (RuntimeException e) {
                count(CacheCounters.Counter.CENTRAL_ERROR);
                if (!centralSpec.failOpen()) {
                    throw e;
                }
                return;
            }
        }
        try {
            central.evict(cacheKey);
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
        } finally {
            local.evict(cacheKey);
            count(CacheCounters.Counter.EVICT);
        }
    }

    // ------------------------------------------------------------------ 版本对账

    /**
     * 命名空间级对账信号量：到点时由当前访问线程对账一次，且只处理
     * 「当前访问的 key 所属分区」的本地副本（见 {@link LocalCacheSpec.VersionSpec#reconcilePartitions()}）。
     *
     * @param accessedKey 当前访问的 key（决定分区）
     * @param now         当前纳秒
     */
    private void maybeReconcile(CacheKey accessedKey, long now) {
        LocalCacheSpec.VersionSpec version = localSpec.version();
        if (version == null) {
            return;
        }
        long next = nextCheckAtNanos;
        if (next == 0L) {
            // 首次访问只设点，不做 I/O
            nextCheckAtNanos = now + jittered(version);
            return;
        }
        if (now - next < 0L) {
            return;
        }
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            ReconcileOutcome outcome = reconcile(version, accessedKey);
            nextCheckAtNanos = now + jittered(version);
            count(CacheCounters.Counter.VERSION_SYNC);
            LOG.debug(CacheLogger.Subject.TWO_LEVEL, "对账", () -> "namespace=" + namespace
                    + "，分区=" + outcome.describePartition()
                    + "，对账完成，清除失效副本 " + outcome.invalidated() + " 条");
        } catch (RuntimeException e) {
            nextCheckAtNanos = now + version.retryBackoff().toNanos();
            count(CacheCounters.Counter.VERSION_SYNC_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warnThrottled(CacheLogger.Subject.TWO_LEVEL, "对账", () -> "版本对账失败，按退避推迟，"
                    + "namespace=" + namespace + "，退避=" + version.retryBackoff()
                    + "，原因=" + e.getMessage());
        } finally {
            reconciling.set(false);
        }
    }

    /**
     * 一次对账：只校验「当前访问 key 所属分区」里本地已知的副本。
     *
     * @param version     版本参数
     * @param accessedKey 当前访问的 key（决定分区）
     * @return 对账结果
     */
    private ReconcileOutcome reconcile(LocalCacheSpec.VersionSpec version, CacheKey accessedKey) {
        Set<CacheKey> keys = local.keys();
        if (keys.isEmpty()) {
            return new ReconcileOutcome(0, 0, 1);
        }
        // 分区数自适应：本地条目装得下一批时直接不分片 —— 一次往返就能全查完，
        // 分区只会白白降低单次覆盖度（每个 key 虽然仍能在自己被读时校验，但没必要的分散）。
        int partitions = effectivePartitions(keys.size(),
                version.reconcileBatchSize(), version.reconcilePartitions());
        int partition = HashPartitions.of(accessedKey, partitions);

        List<CacheKey> selected = new ArrayList<>();
        for (CacheKey key : keys) {
            if (HashPartitions.of(key, partitions) == partition) {
                selected.add(key);
            }
        }
        if (selected.isEmpty()) {
            return new ReconcileOutcome(0, partition, partitions);
        }

        int invalidated = 0;
        int batchSize = version.reconcileBatchSize();
        for (int from = 0; from < selected.size(); from += batchSize) {
            List<CacheKey> batch = selected.subList(from, Math.min(selected.size(), from + batchSize));
            // 只取版本，不取数据值
            Map<CacheKey, Long> latest = central.revisions(batch);
            for (CacheKey cacheKey : batch) {
                Long revision = latest.get(cacheKey);
                if (revision == null) {
                    // 中央已不再持有（删除 / 清空 / 数据过期）
                    local.evict(cacheKey);
                    count(CacheCounters.Counter.VERSION_INVALIDATION);
                    invalidated++;
                    continue;
                }
                LocalEntry<V> entry = local.entry(cacheKey);
                if (entry != null && entry.revision() != revision) {
                    local.evict(cacheKey);
                    count(CacheCounters.Counter.VERSION_INVALIDATION);
                    invalidated++;
                }
            }
        }
        return new ReconcileOutcome(invalidated, partition, partitions);
    }

    /**
     * 自适应分区数：保证「每个分区尽量只装一批」，且不超过配置上限。
     *
     * <ul>
     *   <li>本地条目数 ≤ 一批：返回 {@code 1}（不分片，一次往返覆盖全部本地副本）；</li>
     *   <li>否则取 {@code ceil(本地条目数 / batchSize)} 向上取整到 2 的幂，并封顶到配置值 ——
     *       既让单次对账通常是 1 批，又尽量让一次覆盖更多 key。</li>
     * </ul>
     *
     * @param localSize   本地当前条目数
     * @param batchSize   单批大小
     * @param configured  配置的分区上限（2 的幂）
     * @return 生效分区数（2 的幂，≥ 1）
     */
    static int effectivePartitions(int localSize, int batchSize, int configured) {
        if (configured <= 1 || batchSize <= 0 || localSize <= batchSize) {
            return 1;
        }
        int needed = (localSize + batchSize - 1) / batchSize;
        int pow2 = Integer.highestOneBit(needed);
        if (pow2 < needed) {
            pow2 <<= 1;
        }
        return Math.max(1, Math.min(configured, pow2));
    }

    /**
     * 对账结果。
     *
     * @param invalidated 清除的本地副本数
     * @param partition   本次处理的分区号
     * @param partitions  生效分区数（1 表示未分片）
     */
    private record ReconcileOutcome(int invalidated, int partition, int partitions) {

        /**
         * @return 适合写进日志的分区描述
         */
        String describePartition() {
            return partitions <= 1 ? "未分片" : partition + "/" + partitions;
        }
    }

    private long jittered(LocalCacheSpec.VersionSpec version) {
        long base = version.checkInterval().toNanos();
        double jitter = version.jitter();
        if (jitter <= 0D) {
            return base;
        }
        double factor = 1D + (ThreadLocalRandom.current().nextDouble() * 2D - 1D) * jitter;
        return Math.max(1L, (long) (base * factor));
    }

    // ------------------------------------------------------------------ 工具

    private CacheKey encode(K key) {
        return new CacheKey(namespace, keyEncoder.encode(key));
    }

    private void count(CacheCounters.Counter counter) {
        if (counters != null) {
            counters.increment(counter);
        }
    }

    private Lookup<V> await(CompletableFuture<Lookup<V>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /**
     * @return 毫秒时钟，供子层（如中央实现）读取
     */
    long currentTimeMillis() {
        return milliClock.getAsLong();
    }

    /**
     * 构建器。
     *
     * @param <K> key 类型
     * @param <V> 值类型
     * @author Asial Jim
     */
    public static final class Builder<K, V> {

        private final String namespace;
        private KeyEncoder keyEncoder = com.asialjim.cache4j.core.KeyEncoders.canonical();
        private LocalCache<V> local;
        private CentralCache<V> central;
        private HotKeyCoordinator hotKeyCoordinator;
        private boolean statsEnabled = true;
        private LongSupplier nanoClock = System::nanoTime;
        private LongSupplier milliClock = System::currentTimeMillis;

        Builder(String namespace) {
            this.namespace = namespace;
        }

        /**
         * @param value key 编码器
         * @return this
         */
        public Builder<K, V> keyEncoder(KeyEncoder value) {
            this.keyEncoder = value;
            return this;
        }

        /**
         * @param value 本地缓存（含策略与版本配置）
         * @return this
         */
        public Builder<K, V> local(LocalCache<V> value) {
            this.local = value;
            return this;
        }

        /**
         * @param value 中央缓存视图
         * @return this
         */
        public Builder<K, V> central(CentralCache<V> value) {
            this.central = value;
            return this;
        }

        /**
         * @param value 热 key 协调器；{@code DYNAMIC + GLOBAL} 时必填
         * @return this
         */
        public Builder<K, V> hotKeyCoordinator(HotKeyCoordinator value) {
            this.hotKeyCoordinator = value;
            return this;
        }

        /**
         * @param value 是否启用统计
         * @return this
         */
        public Builder<K, V> statsEnabled(boolean value) {
            this.statsEnabled = value;
            return this;
        }

        /**
         * @param value 单调时钟（测试用）
         * @return this
         */
        public Builder<K, V> nanoClock(LongSupplier value) {
            this.nanoClock = value;
            return this;
        }

        /**
         * @param value 毫秒时钟（测试用）
         * @return this
         */
        public Builder<K, V> milliClock(LongSupplier value) {
            this.milliClock = value;
            return this;
        }

        /**
         * 构建并做启动期校验。
         *
         * @return 门面
         */
        public TwoLevelCache<K, V> build() {
            if (local == null) {
                throw invalid("local 不能为空（NONE 策略请使用 NoOpLocalCache）");
            }
            if (central == null) {
                throw invalid("central 不能为空");
            }
            LocalCacheSpec spec = local.spec();
            if (!spec.namespace().equals(namespace)) {
                throw invalid("namespace 不一致：builder=" + namespace + ", local=" + spec.namespace());
            }
            if (!central.spec().namespace().equals(namespace)) {
                throw invalid("namespace 不一致：builder=" + namespace + ", central="
                        + central.spec().namespace());
            }
            spec.validate();
            if (spec.strategy() == LocalStrategy.DYNAMIC
                    && spec.dynamic().coordination() == LocalCacheSpec.Coordination.GLOBAL) {
                if (hotKeyCoordinator == null && !local.coordinationReady()) {
                    throw invalid("coordination=GLOBAL 必须提供 HotKeyCoordinator，不静默退化为单节点模式");
                }
            }
            return new DefaultTwoLevelCache<>(this);
        }

        private static CacheException invalid(String message) {
            return new CacheException(CacheResCode.INVALID_SPEC, message);
        }
    }
}
