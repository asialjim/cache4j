package com.asialjim.cache4j.core.twolevel;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.HashPartitions;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 版本对账：把"本地副本是否仍是最新"这件事从编排类里独立出来。
 *
 * <p>它天然属于<strong>编排层</strong>而非本地层——对账要同时读本地副本与中央版本表，
 * 而本地层不该知道中央的存在。</p>
 *
 * <h2>触发方式：调用即对账</h2>
 *
 * <p>{@link #maybeReconcile} 内部自己判断"到点没到点"，调用方<strong>不需要</strong>、
 * 也<strong>不允许</strong>先问再调——否则每个调用点都要复制一遍信号量逻辑。</p>
 *
 * <h2>范围：按本地规模自适应</h2>
 *
 * <ul>
 *   <li>本地条目 ≤ 批大小：全量对账（一次 {@code HMGET} 装得下）；</li>
 *   <li>超出批大小：只对账「与当前访问 key 同模」的分区（{@code reconcilePartitions}）。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class Reconciler<V> {

    private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);

    private static final String SUBJECT = "统一中央缓存";

    private final String namespace;

    private final LocalCache<V> local;

    /**
     * 中央版本读取器：<strong>本地层不需要知道"中央"是什么</strong>，只需要"给我这批 key 的权威版本"。
     *
     * <p>由门面注入（{@code central::revisions}），本地层因此不依赖任何中央实现。</p>
     */
    private final Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader;

    /**
     * 版本参数；{@code null} 表示该 namespace 不做对账。
     */
    private final LocalCacheSpec.VersionSpec version;

    private final boolean failOpen;

    private final int partitions;

    /**
     * key → 分区号（首次访问时算一次并保存）。
     */
    private final ConcurrentHashMap<CacheKey, Integer> keyPartitions = new ConcurrentHashMap<>();

    /**
     * 分区号 → 该分区下的本地 key（对账时按分区取，避免遍历 {@code local.keys()}）。
     */
    private final Map<Integer, Set<CacheKey>> localPartitions = new ConcurrentHashMap<>();

    private volatile long nextCheckAtNanos;

    private final AtomicBoolean reconciling = new AtomicBoolean();

    /**
     * @param namespace     命名空间
     * @param local         所属的本地缓存（读 keys、失效条目）
     * @param versionReader 中央版本读取器
     * @param version       版本对账配置；{@code null} 表示不对账
     * @param failOpen      中央不可用时是否放行
     */
    public Reconciler(String namespace, LocalCache<V> local,
                      Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader,
                      LocalCacheSpec.VersionSpec version, boolean failOpen) {
        this.namespace = namespace;
        this.local = local;
        this.versionReader = versionReader;
        this.version = version;
        this.failOpen = failOpen;
        this.partitions = version == null ? 1 : version.reconcilePartitions();
    }

    /**
     * 取 key 的分区号：<strong>第一次访问时计算并保存</strong>，之后直接命中缓存，不再算 hash。
     *
     * @param cacheKey key
     * @return 分区号；本地不可用时恒为 0
     */
    public int partitionOf(CacheKey cacheKey) {
        if (!local.available()) {
            // 没有本地副本也就没有"分区"可言；保存分区号只会无界增长
            return 0;
        }
        Integer saved = keyPartitions.get(cacheKey);
        if (saved != null) {
            return saved;
        }
        int partition = HashPartitions.of(cacheKey, partitions);
        Integer previous = keyPartitions.putIfAbsent(cacheKey, partition);
        return previous != null ? previous : partition;
    }

    /**
     * 登记一个刚写入本地的 key。
     *
     * @param cacheKey key
     */
    public void track(CacheKey cacheKey) {
        localPartitions.computeIfAbsent(partitionOf(cacheKey), p -> ConcurrentHashMap.newKeySet())
                .add(cacheKey);
    }

    /**
     * 忘掉某个 key 的分区信息（分区号 + 分区索引两处）。
     *
     * @param cacheKey key
     */
    public void forget(CacheKey cacheKey) {
        Integer partition = keyPartitions.remove(cacheKey);
        if (partition != null) {
            Set<CacheKey> bucket = localPartitions.get(partition);
            if (bucket != null) {
                bucket.remove(cacheKey);
            }
        }
    }

    /**
     * 整批清空索引（{@code local.clear()} 时调用，存储层不逐条回调）。
     */
    public void clear() {
        keyPartitions.clear();
        localPartitions.clear();
    }

    /**
     * 到点则当场对账后返回；未到点直接返回。
     *
     * @param accessedKey 当前访问的 key（决定对账分区）
     * @param now         当前纳秒
     */
    public void maybeReconcile(CacheKey accessedKey, long now) {
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
            ReconcileOutcome outcome = reconcile(accessedKey);
            nextCheckAtNanos = now + jittered(version);
            LOG.debug("[{}]-{}-{}", SUBJECT, "对账", "namespace=" + namespace
                    + "，范围=" + outcome.describeScope()
                    + "，对账完成，清除失效副本 " + outcome.invalidated() + " 条");
        } catch (RuntimeException e) {
            // 中央故障期不该比对账间隔更频繁地重试
            nextCheckAtNanos = now + Math.max(version.retryBackoff().toNanos(),
                    version.checkInterval().toNanos());
            if (!failOpen) {
                throw e;
            }
            LOG.warn("[{}]-{}-{}", SUBJECT, "对账", "版本对账失败，按退避推迟，namespace=" + namespace
                    + "，退避=" + version.retryBackoff() + "，原因=" + e.getMessage());
        } finally {
            reconciling.set(false);
        }
    }

    /**
     * 一次对账。
     *
     * @param accessedKey 当前访问的 key（分区模式下决定分区号）
     * @return 对账结果
     */
    private ReconcileOutcome reconcile(CacheKey accessedKey) {
        int batchSize = version.reconcileBatchSize();
        // 本地副本总数直接来自索引（O(分区数)），不用遍历 local.keys()
        int localSize = 0;
        for (Set<CacheKey> bucket : localPartitions.values()) {
            localSize += bucket.size();
        }
        if (localSize == 0) {
            return new ReconcileOutcome(0, true, 0, 1, 0);
        }

        // 本地装得下一批 → 全量；超出批大小 → 只取与访问 key 同模的分区
        boolean wholeScope = localSize <= batchSize;
        int scopePartitions = wholeScope ? 1 : version.reconcilePartitions();
        int partition = wholeScope ? 0 : partitionOf(accessedKey);

        List<CacheKey> selected = new ArrayList<>(Math.min(localSize, batchSize));
        if (wholeScope) {
            for (Set<CacheKey> bucket : localPartitions.values()) {
                selected.addAll(bucket);
            }
        } else {
            Set<CacheKey> bucket = localPartitions.get(partition);
            if (bucket != null) {
                selected.addAll(bucket);
            }
        }
        if (selected.isEmpty()) {
            return new ReconcileOutcome(0, wholeScope, partition, scopePartitions, 0);
        }

        int invalidated = 0;
        for (int from = 0; from < selected.size(); from += batchSize) {
            List<CacheKey> batch = selected.subList(from, Math.min(selected.size(), from + batchSize));
            // 只取版本，不取数据值
            Map<CacheKey, Long> latest = versionReader.apply(batch);
            for (CacheKey cacheKey : batch) {
                Long revision = latest.get(cacheKey);
                if (revision == null) {
                    // 中央已不再持有（删除 / 清空 / 数据过期）
                    evictLocal(cacheKey);
                    invalidated++;
                    continue;
                }
                LocalEntry<V> entry = local.peek(cacheKey);    // 内部读，不计本地命中率
                if (entry == null) {
                    // 兜底：存储层尚未回调（如惰性过期未被访问）时，这里顺手剔除幽灵条目
                    local.evict(cacheKey);
                    forget(cacheKey);
                    continue;
                }
                if (entry.revision() != revision) {
                    evictLocal(cacheKey);
                    invalidated++;
                }
            }
        }
        return new ReconcileOutcome(invalidated, wholeScope, partition, scopePartitions, selected.size());
    }

    private void evictLocal(CacheKey cacheKey) {
        local.evict(cacheKey);        // 存储层会回调 forget，这里再兜一次，幂等
        forget(cacheKey);
    }

    private static long jittered(LocalCacheSpec.VersionSpec version) {
        long base = version.checkInterval().toNanos();
        long jitterNanos = version.jitter() == null ? 0L : version.jitter().toNanos();
        if (jitterNanos <= 0L) {
            return base;
        }
        long delta = ThreadLocalRandom.current().nextLong(-jitterNanos, jitterNanos + 1L);
        return Math.max(1L, base + delta);
    }

    /**
     * 对账结果。
     *
     * @param invalidated 清除的本地副本数
     * @param wholeScope  是否全量对账
     * @param partition   分区号（全量时为 0）
     * @param partitions  分区数（全量时为 1）
     * @param scopeSize   本次覆盖的 key 数
     */
    private record ReconcileOutcome(int invalidated, boolean wholeScope,
                                    int partition, int partitions, int scopeSize) {

        String describeScope() {
            return wholeScope ? "全量" : "分区 " + partition + "/" + partitions;
        }
    }
}
