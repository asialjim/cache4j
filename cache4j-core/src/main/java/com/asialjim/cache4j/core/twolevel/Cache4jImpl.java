package com.asialjim.cache4j.core.twolevel;

import com.asialjim.cache4j.core.local.NoneLocalCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.HashPartitions;
import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.CacheResCode;
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
public final class Cache4jImpl<K, V> implements Cache4j<K, V> {

    /**
     * 构造器入口。
     *
     * @param namespace 缓存名
     * @param <K>       key 类型
     * @param <V>       值类型
     * @return 构建器
     */
    public static <K, V> Builder<K, V> builder(String namespace) {
        return new Builder<>(namespace);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Cache4jImpl.class);

    private final String namespace;
    private final KeyEncoder keyEncoder;
    private final LocalCache<V> local;
    private final CentralCache<V> central;
    private final LocalCacheSpec localSpec;
    private final CentralCacheSpec centralSpec;
    private final LongSupplier nanoClock;
    private final LongSupplier milliClock;
    private final com.asialjim.cache4j.core.coordination.SingleFlight<CacheKey, V> singleFlight =
            new com.asialjim.cache4j.core.coordination.LocalSingleFlight<>();

    /**
     * 每个 key 的分区号：<strong>首次访问该 key 时计算一次并保存在本地</strong>，之后对账直接取，
     * 不再重复算 hash。
     */
    private final Map<CacheKey, Integer> keyPartitions = new ConcurrentHashMap<>();

    /**
     * 分区数（2 的幂）：由 {@code version.reconcilePartitions} 决定；分区号范围 {@code [0, partitions)}。
     */

    /**
     * 本地空值副本的除子 N：{@code localNullTtl = min(localTtl, centralNullTtl / N)}。
     *
     * <p>空值副本只是"防穿透的临时标记"，比中央活得久毫无意义；让本地比中央<strong>更早</strong>
     * 放下这个标记，避免"中央已能重新回源、本地还在说不存在"。来自策略仓库的
     * {@code NamespacePolicy.localNullTtlDivisor}，默认 10。</p>
     */
    private final int localNullTtlDivisor;

    /**
     * 本地副本的<strong>分区索引</strong>：分区号 → 该分区内、确实持有副本的 key（空桶惰性创建）。
     *
     * <p>对账时直接 {@code get(partition)} 取，不必遍历 {@code local.keys()} 重算分区；
     * 存储层自己的过期/淘汰造成的"幽灵条目"会在对账比对阶段顺手剔除（见 {@code reconcile}）。</p>
     */

    /**
     * 命名空间级对账信号量：下一次对账时间点（纳秒）；0 表示尚未初始化。
     */

    /**
     * 同一命名空间同一时刻只允许一个线程对账。
     */

    private Cache4jImpl(Builder<K, V> builder) {
        this.namespace = builder.namespace;
        this.keyEncoder = builder.keyEncoder;
        this.local = builder.local;
        this.central = builder.central;
        this.localSpec = local.spec();
        this.centralSpec = central.spec();
        this.nanoClock = builder.nanoClock;
        this.milliClock = builder.milliClock;

        this.localNullTtlDivisor = Math.max(1, builder.localNullTtlDivisor);

        LOG.debug("[{}]-{}-{}", "统一中央缓存", "装配", "namespace=" + namespace
                + "，strategy=" + localSpec.strategy()
                + "，maxSize=" + localSpec.maxSize()
                + "，localTtl=" + localSpec.ttl()
                + "，centralTtl=" + centralSpec.ttl()
                + "，nullTtl=" + centralSpec.nullTtl()
                + "，failOpen=" + centralSpec.failOpen());
    }

    // ------------------------------------------------------------------ Cache

    @Override
    public String name() {
        return namespace;
    }

    @Override
    public Lookup<V> lookup(K key) {
        CacheKey cacheKey = encode(key);
        ReadResult<V> result = read(cacheKey);
        return result.lookup();
    }

    @Override
    public Lookup<V> lookup(K key, CacheLoader<K, V> loader) {
        CacheKey cacheKey = encode(key);

        ReadResult<V> first = read(cacheKey);
        if (first.source() != Source.NONE) {
            return first.lookup();
        }
        // 需要回源：交给 SingleFlight 保证"同一 key 只有一个线程加载并写缓存"
        return singleFlight.once(cacheKey, () -> {
            ReadResult<V> again = read(cacheKey);    // 双检：等锁期间可能已被别人写入
            if (again.source() != Source.NONE) {
                return again.lookup();
            }
            V value;
            try {
                value = loader.load(key);
            } catch (RuntimeException e) {
                LOG.warn("[{}]-读-[{}]-{}", "统一中央缓存", cacheKey.masked(),
                        "loader 执行失败，异常原样抛出，原因="
                                + e.getClass().getSimpleName() + ":" + e.getMessage());
                throw e;
            }
            return value == null ? writeNegative(cacheKey) : writeValue(cacheKey, value, null);
        });
    }

    @Override
    public boolean containsKey(K key) {
        // 探测语义：不改访问频次、不计读指标、不触发回填
        CacheKey cacheKey = encode(key);
        if (local.peek(cacheKey) != null) {
            return true;
        }
        VersionedLookup<V> centralLookup = centralLookup(cacheKey);
        return centralLookup != null && !centralLookup.lookup().isMiss();
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
            putLocal(cacheKey, LocalEntry.hit(value, revision.get()), effective, "写中央并写本地", true);
            return true;
        } catch (RuntimeException e) {
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
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warn("[{}]-{}-{}", "统一中央缓存", "失效", "中央失效失败，降级清理本地，key="
                    + cacheKey.masked() + "，原因=" + e.getMessage());
        } finally {
            local.evict(cacheKey);
        }
    }

    @Override
    public void clear() {
        try {
            central.clear();
        } catch (RuntimeException e) {
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warn("[{}]-{}-{}", "统一中央缓存", "清空", "中央清空失败，降级清理本地，"
                    + "namespace=" + namespace + "，原因=" + e.getMessage());
        } finally {
            local.clear();
        }
    }

    @Override
    public void close() {
        LOG.debug("[{}]-{}-{}", "统一中央缓存", "关闭", "namespace=" + namespace + "，关闭门面，取消进行中的回源");
        singleFlight.close();
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

    /**
     * 一次读：<strong>本地 → 中央</strong>，仅此而已。
     *
     * <p>本地层内部已经处理好自己的事——{@code NONE} 策略天然返回未命中（不开启本地 = 未命中）、
     * {@code DYNAMIC} 冷 key 也返回未命中。因此这里<strong>不判断策略、不碰热 key</strong>：
     * 两条路（本地不可用 / 本地可用但未命中）的后续完全一样，都是去查中央。</p>
     *
     * @param cacheKey key
     * @return 读结果
     */
    private ReadResult<V> read(CacheKey cacheKey) {

        Lookup<LocalEntry<V>> localLookup = this.local.lookup(cacheKey);
        if (localLookup instanceof Lookup.Hit<LocalEntry<V>> hit) {
            LocalEntry<V> entry = hit.value();
            return entry.isNegative()
                    ? new ReadResult<>(Lookup.negative(), Source.LOCAL)
                    : new ReadResult<>(Lookup.hit(entry.value()), Source.LOCAL);
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
            if (!centralSpec.failOpen()) {
                throw e;
            }
            LOG.warn("[{}]-读-[{}]-{}", "统一中央缓存", cacheKey.masked(), "中央读取失败，按未命中降级，原因="
                            + e.getClass().getSimpleName() + ":" + e.getMessage());
            return null;
        }
    }

    /**
     * 本地空值副本的存活时长：{@code min(localTtl, centralNullTtl / N)}。
     *
     * <p>{@code centralNullTtl = ZERO}（不缓存空值）时不会被调用——那条路径直接不落本地。</p>
     *
     * @return 生效 TTL
     */
    private Duration localNullTtl() {
        return LocalCacheSpec.deriveNullTtl(localSpec.ttl(), centralSpec.nullTtl(), localNullTtlDivisor);
    }

    /**
     * 回填：只有现有副本的 revision 不大于本次读到的 revision 时才覆盖。
     *
     * <p>比较与写入由 {@link LocalCache#putIfNewer} 原子完成，避免"读版本 → 写副本"之间
     * 被另一个线程的更新版本抢先覆盖（D18 的竞态窗口）。</p>
     */
    /**
     * 回填本地。
     *
     * <p>"该不该接、接不接得下"由本地层自己判断（{@code putIfNewer} 内部含准入与版本比较），
     * 门面只负责把中央读到的值交过去。</p>
     *
     * @param cacheKey key
     * @param entry    条目
     */
    private void backfill(CacheKey cacheKey, LocalEntry<V> entry) {
        putLocal(cacheKey, entry, entry.isNegative() ? localNullTtl() : null,
                entry.isNegative() ? "负副本回填到本地" : "回填到本地");
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
    private boolean putLocal(CacheKey cacheKey, LocalEntry<V> entry, Duration effective, String detail) {
        return putLocal(cacheKey, entry, effective, detail, false);
    }

    /**
     * 写本地。
     *
     * @param cacheKey  key
     * @param entry     条目
     * @param effective 生效 TTL（可空）
     * @param detail    详情文案
     * @param writeOp   {@code true} 表示调用方写入（日志用"写"），{@code false} 表示内部回填（日志用"读"）
     * @return 是否真的写入了本地
     */
    private boolean putLocal(CacheKey cacheKey, LocalEntry<V> entry, Duration effective,
                             String detail, boolean writeOp) {
        Duration localTtl = localSpec.ttl();
        Duration chosen = effective != null && localTtl != null && effective.compareTo(localTtl) < 0
                ? effective : localTtl;

        // 条件写入：准入（DYNAMIC 冷 key）与版本比较都在本地实现内部完成
        boolean written = local.putIfNewer(cacheKey, entry, chosen);
        if (written) {
        } else {
            // 本地不持有副本 → 分区号也不保留，避免无界增长
        }
        if (written) {
            String subject = localSubject(localSpec.strategy());
            LOG.debug("[{}]-{}-[{}]-{}", subject, writeOp ? "写" : "读", cacheKey.masked(), detail);
        }
        return written;
    }

    // ------------------------------------------------------------------ 写路径

    private Lookup<V> writeValue(CacheKey cacheKey, V value, Duration ttl) {
        Duration effective = ttl != null ? ttl : centralSpec.ttl();
        long revision;
        try {
            revision = central.putAndGetRevision(cacheKey, value, effective);
        } catch (RuntimeException e) {
            if (!centralSpec.failOpen()) {
                throw e;
            }
            // 默认不写本地：避免"只有我能看到的新值"
            LOG.warn("[{}]-写-[{}]-{}", "统一中央缓存", cacheKey.masked(), "中央写失败，按 failOpen 降级（不写本地），"
                    + "原因=" + e.getClass().getSimpleName() + ":" + e.getMessage());
            return Lookup.hit(value);
        }
        putLocal(cacheKey, LocalEntry.hit(value, revision), effective, "写中央并写本地", true);
        return Lookup.hit(value);
    }

    private Lookup<V> writeNegative(CacheKey cacheKey) {
        if (!centralSpec.nullCachingEnabled()) {
            return Lookup.miss();
        }
        try {
            long revision = central.putNegativeAndGetRevision(cacheKey, centralSpec.nullTtl());
            // 本地负副本用派生 TTL：min(localTtl, centralNullTtl / N)，比中央更早放下标记
            putLocal(cacheKey, LocalEntry.negative(revision), localNullTtl(), "写中央并写本地（空值）", true);
            return Lookup.negative();
        } catch (RuntimeException e) {
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
                // 中央用调用方给的 TTL；本地负副本一律用派生值 min(localTtl, centralNullTtl / N)
                putLocal(cacheKey, LocalEntry.negative(revision), localNullTtl(),
                        "写中央并写本地（空值）", true);
                return;
            } catch (RuntimeException e) {
                if (!centralSpec.failOpen()) {
                    throw e;
                }
                return;
            }
        }
        try {
            central.evict(cacheKey);
        } catch (RuntimeException e) {
            if (!centralSpec.failOpen()) {
                throw e;
            }
        } finally {
            local.evict(cacheKey);
        }
    }

    // ------------------------------------------------------------------ 版本对账

    // ------------------------------------------------------------------ 工具

    private static String localSubject(LocalStrategy strategy) {
        return switch (strategy) {
            case STATIC -> "静态本地缓存";
            case DYNAMIC -> "动态本地缓存";
            case NONE -> "统一中央缓存";
        };
    }

    private CacheKey encode(K key) {
        return new CacheKey(namespace, keyEncoder.encode(key));
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
        private KeyEncoder keyEncoder = com.asialjim.cache4j.core.KeyEncoders.plain();
        private LocalCache<V> local;
        private CentralCache<V> central;
        private HotKeyCoordinator hotKeyCoordinator;
        private int localNullTtlDivisor = 10;
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
         * 本地空值副本的除子 N（默认 10）：{@code localNullTtl = min(localTtl, centralNullTtl / N)}。
         *
         * @param value 除子，必须 ≥ 1
         * @return this
         */
        public Builder<K, V> localNullTtlDivisor(int value) {
            if (value < 1) {
                throw invalid("localNullTtlDivisor 必须 >= 1：" + value);
            }
            this.localNullTtlDivisor = value;
            return this;
        }

        /**
         * @param value 是否启用统计
         * @return this
         */

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
        public Cache4j<K, V> build() {
            if (local == null) {
                throw invalid("local 不能为空（NONE 策略请使用 NoneLocalCache）");
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
            return new Cache4jImpl<>(this);
        }

        private static CacheException invalid(String message) {
            return new CacheException(CacheResCode.INVALID_SPEC, message);
        }
    }
}
