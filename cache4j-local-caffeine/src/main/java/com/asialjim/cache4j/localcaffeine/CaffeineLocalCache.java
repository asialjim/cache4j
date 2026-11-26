package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.LocalEntry;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.StaticLocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.twolevel.Reconciler;
import com.asialjim.cache4j.core.local.VersionedLocalCache;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.function.Function;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 基于 Caffeine 的本地存储实现。
 *
 * <p>与 HashMap 实现相比：全局 LRU（Window TinyLFU）淘汰、每条目独立 TTL、命中率/淘汰统计更准；
 * 代价是引入 Caffeine 依赖，所以只放在 {@code cache4j-local-caffeine}。</p>
 *
 * <p>它<strong>不改变</strong>策略语义：{@code DYNAMIC} 的热点门控仍由
 * {@code DynamicLocalCache} 装饰器完成，Caffeine 只回答"容量满时淘汰谁"。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
/**
 * {@code STATIC} 策略的本地缓存——<strong>Caffeine 存储</strong>。
 *
 * <p>非 {@code final}：{@link CaffeineDynamicLocalCache} 继承它复用存储与 key 版本管理，
 * 只覆写准入判定（{@code accepts} / {@code access} / {@code hotSync}）。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public class CaffeineLocalCache<V> implements StaticLocalCache<V>, VersionedLocalCache<V> {

    /**
     * 永不过期的 TTL 表达（Caffeine 用纳秒计时）。
     */
    private static final long NEVER = Long.MAX_VALUE;

    private final LocalCacheSpec spec;
    private final com.github.benmanes.caffeine.cache.Cache<CacheKey, TimedEntry<V>> cache;

    /**
     * 单调时钟（对账的时间信号量用它）。
     */
    private final LongSupplier nanoClock;

    /**
     * 版本对账：本地副本的新鲜度是本地自己的事，门面不需要知道。
     */
    private final Reconciler<V> reconciler;

    /**
     * 条目消失（过期 / 容量淘汰 / 显式失效）时的通知，默认空实现。
     */
    private volatile Consumer<CacheKey> removalListener = key -> {
    };

    /**
     * @param spec      配置
     * @param nanoClock 单调时钟（同时作为 Caffeine 的 ticker，便于测试注入）
     */
    public CaffeineLocalCache(LocalCacheSpec spec, LongSupplier nanoClock,
                             Function<Collection<CacheKey>, Map<CacheKey, Long>> versionReader, boolean failOpen) {
        this.spec = spec;
        this.nanoClock = nanoClock;
        Ticker ticker = nanoClock::getAsLong;
        this.cache = Caffeine.newBuilder()
                .maximumSize(spec.maxSize())
                .ticker(ticker)
                .expireAfter(new Expiry<CacheKey, TimedEntry<V>>() {
                    @Override
                    public long expireAfterCreate(CacheKey key, TimedEntry<V> value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(CacheKey key, TimedEntry<V> value,
                                                  long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(CacheKey key, TimedEntry<V> value,
                                                long currentTime, long currentDuration) {
                        // v1 只支持写后过期：读不续期
                        return currentDuration;
                    }
                })
                .removalListener((CacheKey key, TimedEntry<V> value, RemovalCause cause) -> {
                    // 覆盖写（REPLACED）时条目仍在，不能通知；整批清空（clear）由调用方整体处理
                    if (key != null && cause != RemovalCause.REPLACED) {
                        removalListener.accept(key);
                    }
                })
                // 默认 executor 会把回调派到线程池，条目消失要立刻反映到调用方的分区表 → 就地同步执行
                .executor(Runnable::run)
                .recordStats()
                .build();
        this.reconciler = new Reconciler<>(spec.namespace(), this, versionReader, spec.version(), failOpen);
        // 条目过期 / 淘汰 / 失效时立即清掉分区号与分区索引，不等下一次对账才发现
        onEntryRemoved(reconciler::forget);
    }

    @Override
    public LocalStrategy strategy() {
        return spec.strategy();
    }

    @Override
    public LocalCacheSpec spec() {
        return spec;
    }

    @Override
    public LocalEntry<V> entryOf(CacheKey key) {
        TimedEntry<V> timed = cache.getIfPresent(key);   // 不递增本实现的 STORE_HIT/MISS
        return timed == null ? null : timed.entry();
    }

    @Override
    public Lookup<V> lookup(CacheKey key) {
        reconciler.maybeReconcile(key, nanoClock.getAsLong());   // 对账搭在读路径上
        TimedEntry<V> timed = cache.getIfPresent(key);
        if (timed == null) {
            return Lookup.miss();
        }
        return toLookup(timed.entry());
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public void put(CacheKey key, V value, Duration ttl) {
        cache.put(key, new TimedEntry<>(entryOf(value, 0L), toNanos(ttl)));
        reconciler.track(key);        // 登记对账分区
    }

    @Override
    public boolean putIfNewer(CacheKey key, V value, long revision, Duration ttl) {
        AtomicBoolean written = new AtomicBoolean();
        cache.asMap().compute(key, (ignored, existing) -> {
            if (revision != 0 && existing != null && existing.entry().revision() > revision) {
                return existing;             // 本地已有更新版本，丢弃本次
            }
            written.set(true);
            return new TimedEntry<>(entryOf(value, revision), toNanos(ttl));
        });
        if (written.get()) {
            reconciler.track(key);    // 登记对账分区
        }
        return written.get();
    }

    @Override
    public boolean putIfAbsent(CacheKey key, V value, Duration ttl) {
        TimedEntry<V> previous = cache.asMap().putIfAbsent(key, new TimedEntry<>(entryOf(value, 0L), toNanos(ttl)));
        if (previous != null) {
            return false;
        }
        return true;
    }

    @Override
    public void evict(CacheKey key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        reconciler.clear();
        cache.invalidateAll();
    }

    @Override
    public Set<CacheKey> keys() {
        return new HashSet<>(cache.asMap().keySet());
    }

    @Override
    public void maintain() {
        cache.cleanUp();
    }

    @Override
    public void onEntryRemoved(Consumer<CacheKey> listener) {
        this.removalListener = listener == null ? key -> {
        } : listener;
    }

    @Override
    public void close() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    private static long toNanos(Duration ttl) {
        return ttl == null ? NEVER : Math.max(1L, ttl.toNanos());
    }

    /**
     * 带 TTL 的条目包装：Caffeine 的 {@link Expiry} 从 value 里读每条目的存活时间。
     *
     * @param entry   本地副本
     * @param ttlNanos 存活纳秒数；{@link Long#MAX_VALUE} 表示不过期
     * @param <V>     值类型
     */
    /**
     * 值 + 版本 → 本地条目。{@code value == null} 表示"确认不存在"（负副本）。
     */
    private static <V> LocalEntry<V> entryOf(V value, long revision) {
        return value == null ? LocalEntry.negative(revision) : LocalEntry.hit(value, revision);
    }

    /**
     * 本地条目 → 读结果。版本在这里被丢掉：调用方只关心"有值 / 确认没有 / 不知道"。
     */
    private static <V> Lookup<V> toLookup(LocalEntry<V> entry) {
        return entry.isNegative() ? Lookup.negative() : Lookup.hit(entry.value());
    }

    private record TimedEntry<V>(LocalEntry<V> entry, long ttlNanos) {
    }
}
