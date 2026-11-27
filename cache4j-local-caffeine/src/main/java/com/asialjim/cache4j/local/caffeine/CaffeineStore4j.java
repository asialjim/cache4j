package com.asialjim.cache4j.local.caffeine;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.local.BoundedMap4j;
import com.asialjim.cache4j.strategy.CacheStrategy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Caffeine 存储层：只管"把值放进去 / 取出来 / 删掉"，不参与任何策略判断。
 *
 * <p>{@code STATIC} 与 {@code DYNAMIC} 两个骨架共用它——两者的差别只在"谁有资格写进来"
 * （STATIC 读和写都写；DYNAMIC 只由读路径回填），存储细节完全一样。</p>
 *
 * <h2>key 的形态</h2>
 * <p>存的是 {@link Cache4jKey#localKey()}（{@code C4J-L1:ns:key:version}）——
 * <strong>版本进 key</strong>：版本一变 key 就变，旧副本自然不再命中，不需要"读时比版本"。</p>
 * <p>代价是删除必须知道"当前版本"，因此额外维护一张
 * {@code 逻辑 key → 最近写入的 localKey} 的映射（容量 = {@code localMaxSize}，LRU）。
 * 这张表只用于删除，不参与读写判定。</p>
 *
 * <h2>TTL</h2>
 * <p>用 Caffeine 的 {@link Expiry} 做<strong>每条目</strong>的过期策略，
 * 因为正副本与负副本的 TTL 不同，而 {@code expireAfterWrite} 是全局的：</p>
 * <ul>
 *   <li>正副本：{@code localTtl}</li>
 *   <li>负副本：{@code min(localTtl, centralNullTtl / divisor)}——比中央更早放下标记，
 *       避免"中央已能重新回源、本地还在说不存在"</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
final class CaffeineStore4j<V> {

    /**
     * 存进去的条目：{@code value == null} 表示"确认不存在"的负副本。
     *
     * <p>{@code logicalKey} 随条目一起存：caffeine 的 key 是 {@code localKey()}（带版本），
     * {@code removalListener} 要回报"哪个<strong>逻辑</strong> key 消失了"就得靠它反查。</p>
     */
    private record Stored<V>(V value, long version, String logicalKey) {
        boolean negative() {
            return value == null;
        }
    }

    private final Cache<String, Stored<V>> cache;

    /**
     * 逻辑 key → 最近一次写入的 localKey，只服务删除。
     */
    private final BoundedMap4j<String, String> localKeys;

    /**
     * 策略快照（TTL 计算用）。
     */
    private final CacheStrategy strategy;

    /**
     * @param strategy        策略（TTL 与容量从这里取）
     * @param removalListener 条目自己消失时的回调，参数是逻辑 key；不允许 {@code null}
     */
    CaffeineStore4j(CacheStrategy strategy, Consumer<String> removalListener) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        // 先于 cache 赋值：下面的 removalListener lambda 会读它
        //this.removalListener = Objects.requireNonNull(removalListener, "removalListener");
        long maxSize = Math.max(1L, longOf(strategy.localMaxSize(), 1_024L));
        this.localKeys = BoundedMap4j.lru(Math.toIntExact(Math.min(maxSize, Integer.MAX_VALUE)));
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, Stored<V>>() {
                    @Override
                    public long expireAfterCreate(@NonNull String key, @NonNull Stored<V> value, long currentTime) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterUpdate(@NonNull String key, @NonNull Stored<V> value, long currentTime,
                                                  long currentDuration) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterRead(@NonNull String key, @NonNull Stored<V> value, long currentTime,
                                                long currentDuration) {
                        // 读不续期：本地条目的寿命只由写入时刻决定，避免"越读越不过期"
                        return currentDuration;
                    }
                })
                .removalListener((String key, Stored<V> stored, RemovalCause cause) -> {
                    // REPLACED：覆盖写，新值还在，条目没有消失
                    // EXPLICIT：invalidate 触发，是我们主动删的，不需要反过来通知（否则会递归）
                    //noinspection ConstantValue
                    if (Objects.isNull(stored) || cause == RemovalCause.REPLACED || cause == RemovalCause.EXPLICIT) {
                        return;
                    }
                    // EXPIRED / SIZE：条目真的没了 —— 通知上层收缩分区索引与版本表
                    removalListener.accept(stored.logicalKey());
                })
                // 默认 executor 会派到 ForkJoinPool：条目消失必须立刻反映到上层的分区表，
                // 且回调里会读本类的映射表 —— 跨线程既滞后又难排查，就地同步执行
                .executor(Runnable::run)
                .build();
    }


    /**
     * 读。版本已经过 key 的形态区分，这里只按 {@code localKey()} 取。
     *
     * @param key 带版本的 key
     * @return 三态结果
     */
    Lookup<V> load(Cache4jKey key) {
        Stored<V> stored = cache.getIfPresent(key.localKey());
        if (stored == null) {
            return Lookup.miss();
        }
        return stored.negative()
                ? Lookup.negative(stored.version())
                : Lookup.hit(stored.version(), stored.value());
    }

    /**
     * 写正副本。
     *
     * @param key   带版本的 key
     * @param value 值，不为 {@code null}
     */
    void store(Cache4jKey key, V value) {
        Objects.requireNonNull(value, "value");
        String local = key.localKey();
        cache.put(local, new Stored<>(value, versionOf(key), key.key()));
        localKeys.put(key.key(), local);
    }

    /**
     * 写负副本。
     *
     * @param key 带版本的 key
     */
    void storeNegative(Cache4jKey key) {
        String local = key.localKey();
        cache.put(local, new Stored<>(null, versionOf(key), key.key()));
        localKeys.put(key.key(), local);
    }

    /**
     * 按<strong>逻辑</strong> key 删除：借映射表定位到实际的 {@code localKey}。
     *
     * <p>映射表里没有记录时什么也不做——说明这条从未以当前版本写入过（可能已被 TTL 清掉，
     * 也可能本来就不是本地副本）。</p>
     *
     * @param logicalKey 逻辑 key
     */
    void delete(String logicalKey) {
        String local = localKeys.remove(logicalKey);
        // 即便映射表被 LRU 挤掉，也尝试用"当前已知版本"拼一次——尽力而为，多删一次无害
        if (local != null) {
            cache.invalidate(local);
        }
    }

    /**
     * 清空存储与映射。
     */
    void clear() {
        cache.invalidateAll();
        localKeys.clear();
    }

    /**
     * @return 当前条目数（观测用）
     */
    long estimatedSize() {
        return cache.estimatedSize();
    }

    private long ttlNanos(Stored<V> stored) {
        if (!stored.negative()) {
            return localTtlNanos();
        }
        long centralNull = centralNullTtlNanos();
        if (centralNull <= 0L) {
            // 中央不缓存空值：本地也不留负副本（给一个极短寿命，下次读就会失效）
            return TimeUnit.MILLISECONDS.toNanos(1L);
        }
        int divisor = Math.max(1, intOf(strategy.localNullTtlDivisor(), 10));
        long derived = centralNull / divisor;
        long local = localTtlNanos();
        return local > 0L ? Math.min(derived, local) : derived;
    }

    private long localTtlNanos() {
        long amount = Math.max(0L, longOf(strategy.localTtl(), 0L));
        if (amount <= 0L) {
            // 未配置 TTL 时不设过期（由容量淘汰兜底）
            return Long.MAX_VALUE;
        }
        return toNanos(amount);
    }

    private long centralNullTtlNanos() {
        long amount = Math.max(0L, longOf(strategy.centralNullTtl(), 0L));
        if (amount <= 0L) {
            return 0L;
        }
        return toNanos(amount);
    }

    private static long versionOf(Cache4jKey key) {
        Supplier<Long> supplier = key.versionSupplier();
        if (supplier == null) {
            return 0L;
        }
        Long version = supplier.get();
        return version == null ? 0L : version;
    }

    /**
     * 秒 → 纳秒。
     *
     * <p>{@code CacheStrategy} 的所有时长字段一律是秒，不再携带时间单位；
     * 面向运维的单位选择由 {@code CacheStrategyAdministrator} 换算后落库。</p>
     */
    private static long toNanos(long seconds) {
        return seconds * 1_000_000_000L;
    }

    private static long longOf(Supplier<Long> supplier, long fallback) {
        if (supplier == null) {
            return fallback;
        }
        Long value = supplier.get();
        return value == null ? fallback : value;
    }

    private static int intOf(Supplier<Integer> supplier, @SuppressWarnings("SameParameterValue") int fallback) {
        if (supplier == null) {
            return fallback;
        }
        Integer value = supplier.get();
        return value == null ? fallback : value;
    }
}
