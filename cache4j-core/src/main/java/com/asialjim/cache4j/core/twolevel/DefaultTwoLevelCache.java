package com.asialjim.cache4j.core.twolevel;

import com.asialjim.cache4j.core.Cache;
import com.asialjim.cache4j.core.CacheCounters;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.CacheStats;
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

    private static final System.Logger LOG = System.getLogger(DefaultTwoLevelCache.class.getName());

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
            putLocal(cacheKey, LocalEntry.hit(value, revision.get()), effective);
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
            LOG.log(System.Logger.Level.WARNING,
                    () -> "中央失效失败，降级清理本地：" + cacheKey.masked());
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
            LOG.log(System.Logger.Level.WARNING, () -> "中央清空失败，降级清理本地：" + namespace);
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
        counters.setEstimatedSize(local.available() ? local.stats().estimatedSize() : -1L);
        counters.setHotKeyCount(local.stats().hotKeyCount());
        counters.setCandidateCount(local.stats().candidateCount());
        return counters;
    }

    @Override
    public void close() {
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
        if (local.available()) {
            local.access(cacheKey);
            local.hotSync(cacheKey);
            maybeReconcile(now);

            LocalEntry<V> entry = local.entry(cacheKey);
            if (entry != null) {
                return entry.isNegative()
                        ? new ReadResult<>(Lookup.negative(), Source.LOCAL)
                        : new ReadResult<>(Lookup.hit(entry.value()), Source.LOCAL);
            }
        }

        VersionedLookup<V> centralLookup = centralLookup(cacheKey);
        if (centralLookup == null) {
            return new ReadResult<>(Lookup.miss(), Source.NONE);
        }
        if (centralLookup.lookup().isHit()) {
            backfill(cacheKey, LocalEntry.hit(centralLookup.lookup().optional().orElseThrow(),
                    centralLookup.revision()));
            return new ReadResult<>(centralLookup.lookup(), Source.CENTRAL);
        }
        if (centralLookup.lookup().isNegative()) {
            if (centralSpec.nullCachingEnabled()) {
                backfill(cacheKey, LocalEntry.negative(centralLookup.revision()));
            } else {
                local.evict(cacheKey);
            }
            return new ReadResult<>(Lookup.negative(), Source.CENTRAL);
        }
        local.evict(cacheKey);
        return new ReadResult<>(Lookup.miss(), Source.NONE);
    }

    private VersionedLookup<V> centralLookup(CacheKey cacheKey) {
        try {
            return central.lookupWithRevision(cacheKey);
        } catch (RuntimeException e) {
            count(CacheCounters.Counter.CENTRAL_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.log(System.Logger.Level.WARNING,
                    () -> "中央读取失败，按未命中降级：" + cacheKey.masked());
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
    private void backfill(CacheKey cacheKey, LocalEntry<V> entry) {
        if (!local.available()) {
            return;
        }
        LocalEntry<V> current = local.entry(cacheKey);
        if (current != null && current.revision() > entry.revision()) {
            count(CacheCounters.Counter.BACKFILL_DISCARDED);
            return;
        }
        putLocal(cacheKey, entry, entry.isNegative() ? centralSpec.nullTtl() : null);
    }

    private void putLocal(CacheKey cacheKey, LocalEntry<V> entry, Duration effective) {
        if (!local.available()) {
            return;
        }
        Duration localTtl = localSpec.ttl();
        Duration chosen = effective != null && localTtl != null && effective.compareTo(localTtl) < 0
                ? effective : localTtl;
        local.put(cacheKey, entry, chosen);
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
            return Lookup.hit(value);
        }
        count(CacheCounters.Counter.PUT);
        putLocal(cacheKey, LocalEntry.hit(value, revision), effective);
        return Lookup.hit(value);
    }

    private Lookup<V> writeNegative(CacheKey cacheKey) {
        if (!centralSpec.nullCachingEnabled()) {
            return Lookup.miss();
        }
        try {
            long revision = central.putNegativeAndGetRevision(cacheKey, centralSpec.nullTtl());
            putLocal(cacheKey, LocalEntry.negative(revision), centralSpec.nullTtl());
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
                putLocal(cacheKey, LocalEntry.negative(revision), effective);
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

    private void maybeReconcile(long now) {
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
            reconcile(version);
            nextCheckAtNanos = now + jittered(version);
            count(CacheCounters.Counter.VERSION_SYNC);
        } catch (RuntimeException e) {
            nextCheckAtNanos = now + version.retryBackoff().toNanos();
            count(CacheCounters.Counter.VERSION_SYNC_ERROR);
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.log(System.Logger.Level.WARNING,
                    () -> "版本对账失败，按退避推迟：" + namespace);
        } finally {
            reconciling.set(false);
        }
    }

    private void reconcile(LocalCacheSpec.VersionSpec version) {
        Set<CacheKey> keys = local.keys();
        if (keys.isEmpty()) {
            return;
        }
        List<CacheKey> all = new ArrayList<>(keys);
        int batchSize = version.reconcileBatchSize();
        for (int from = 0; from < all.size(); from += batchSize) {
            List<CacheKey> batch = all.subList(from, Math.min(all.size(), from + batchSize));
            // 只取版本，不取数据值
            Map<CacheKey, Long> latest = central.revisions(batch);
            for (CacheKey cacheKey : batch) {
                Long revision = latest.get(cacheKey);
                if (revision == null) {
                    // 中央已不再持有（删除 / 清空 / 数据过期）
                    local.evict(cacheKey);
                    count(CacheCounters.Counter.VERSION_INVALIDATION);
                    continue;
                }
                LocalEntry<V> entry = local.entry(cacheKey);
                if (entry != null && entry.revision() != revision) {
                    local.evict(cacheKey);
                    count(CacheCounters.Counter.VERSION_INVALIDATION);
                }
            }
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
