package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.central.VersionedLookup;
import com.asialjim.cache4j.core.local.LocalCacheSpec;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * {@link RedisValueStore} 的内存参考实现：<strong>仅供测试与单机</strong>。
 *
 * <p>它模拟了适配层应当提供的语义：</p>
 * <ul>
 *   <li>每 key 一个版本（时间戳，写时取 {@code max(now, cur + 1)}），写值与版本原子；</li>
 *   <li>{@link #delete} 连版本一起删，因此对账能观测到"中央已无此 key"；</li>
 *   <li>TTL 到期后 {@code read} 为 miss、{@code revisions} 为缺失；</li>
 *   <li>热 key 双窗口计数 + {@code HOT/ACTIVE/COOLING/UNKNOWN} 状态机。</li>
 * </ul>
 *
 * <p>生产环境由适配层（`cache4j-spring-redis` 等）用真实客户端实现本接口。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class InMemoryValueStore<V> implements RedisValueStore<V> {

    private final ConcurrentHashMap<CacheKey, Entry<V>> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<CacheKey, Window>> hotState =
            new ConcurrentHashMap<>();
    private final Function<String, LocalCacheSpec.GlobalSpec> globalSpecs;
    private final LongSupplier milliClock;
    private final java.util.concurrent.atomic.AtomicInteger revisionsCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger revisionsKeys =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger readCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * @param globalSpecs namespace → 中央阈值；为 {@code null} 时热 key 一律返回 {@code UNKNOWN}
     * @param milliClock  毫秒时钟
     */
    public InMemoryValueStore(Function<String, LocalCacheSpec.GlobalSpec> globalSpecs,
                              LongSupplier milliClock) {
        this.globalSpecs = globalSpecs;
        this.milliClock = milliClock;
    }

    @Override
    public VersionedLookup<V> read(CacheKey key) {
        readCalls.incrementAndGet();
        Entry<V> entry = live(key);
        if (entry == null) {
            return new VersionedLookup<>(0L, Lookup.miss());
        }
        return entry.negative
                ? new VersionedLookup<>(entry.revision, Lookup.negative())
                : new VersionedLookup<>(entry.revision, Lookup.hit(entry.value));
    }

    @Override
    public Map<CacheKey, VersionedLookup<V>> readAll(Collection<CacheKey> keys) {
        Map<CacheKey, VersionedLookup<V>> result = new HashMap<>(keys.size() * 2);
        keys.forEach(key -> result.put(key, read(key)));
        return result;
    }

    @Override
    public long write(CacheKey key, V value, Duration ttl) {
        long now = milliClock.getAsLong();
        synchronized (data) {
            long revision = nextRevision(data.get(key), now);
            data.put(key, new Entry<>(value, false, revision, expireAt(ttl, now)));
            return revision;
        }
    }

    @Override
    public long writeNegative(CacheKey key, Duration ttl) {
        long now = milliClock.getAsLong();
        synchronized (data) {
            long revision = nextRevision(data.get(key), now);
            data.put(key, new Entry<>(null, true, revision, expireAt(ttl, now)));
            return revision;
        }
    }

    @Override
    public OptionalLong writeIfAbsent(CacheKey key, V value, Duration ttl) {
        long now = milliClock.getAsLong();
        synchronized (data) {
            Entry<V> existing = live(key);
            if (existing != null) {
                return OptionalLong.empty();
            }
            long revision = nextRevision(existing, now);
            data.put(key, new Entry<>(value, false, revision, expireAt(ttl, now)));
            return OptionalLong.of(revision);
        }
    }

    @Override
    public void delete(CacheKey key) {
        data.remove(key);
    }

    @Override
    public void clear(String namespace) {
        data.keySet().removeIf(key -> key.namespace().equals(namespace));
        hotState.remove(namespace);
    }

    @Override
    public Map<CacheKey, Long> revisions(Collection<CacheKey> keys) {
        revisionsCalls.incrementAndGet();
        revisionsKeys.addAndGet(keys.size());
        Map<CacheKey, Long> result = new HashMap<>(keys.size() * 2);
        for (CacheKey key : keys) {
            Entry<V> entry = live(key);
            if (entry != null) {
                result.put(key, entry.revision);
            }
        }
        return result;
    }

    @Override
    public long revision(CacheKey key) {
        Entry<V> entry = live(key);
        return entry == null ? 0L : entry.revision;
    }

    @Override
    public HotKeyReport reportHotKey(String namespace, CacheKey key, long delta) {
        LocalCacheSpec.GlobalSpec globalSpec = globalSpecs == null ? null : globalSpecs.apply(namespace);
        if (globalSpec == null || globalSpec.window() == null || globalSpec.window().isZero()) {
            return HotKeyReport.unknown();
        }
        long windowMillis = globalSpec.window().toMillis();
        long windowId = milliClock.getAsLong() / windowMillis;
        Window window = hotState.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            window.rotateTo(windowId);
            if (delta > 0L) {
                window.current += delta;
                window.lastActiveWindow = windowId;
            }
            long total = window.current + window.previous;
            if (delta == 0L && window.lastActiveWindow < 0L && total == 0L) {
                return HotKeyReport.unknown();
            }
            HotKeyStatus status;
            if (window.lastActiveWindow >= 0L
                    && windowId - window.lastActiveWindow >= globalSpec.staleWindows()) {
                status = HotKeyStatus.COOLING;
                window.lowSince = -1L;
            } else if (total >= globalSpec.hotCount()) {
                status = HotKeyStatus.HOT;
                window.lastHotWindow = windowId;
                window.lowSince = -1L;
            } else if (total < globalSpec.coolCount()) {
                if (window.lowSince < 0L) {
                    window.lowSince = windowId;
                }
                status = windowId - window.lowSince + 1L >= globalSpec.demoteWindows()
                        ? HotKeyStatus.COOLING : HotKeyStatus.ACTIVE;
            } else {
                window.lowSince = -1L;
                status = HotKeyStatus.ACTIVE;
            }
            // 只回传状态 + 排障时间戳，计数不回传
            return new HotKeyReport(status, window.lastActiveWindow, window.lastHotWindow);
        }
    }

    @Override
    public Map<CacheKey, HotKeyReport> reportHotKeyAll(String namespace, Map<CacheKey, Long> deltas) {
        Map<CacheKey, HotKeyReport> result = new HashMap<>(deltas.size() * 2);
        deltas.forEach((key, delta) -> result.put(key, reportHotKey(namespace, key, delta)));
        return result;
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public void close() {
        data.clear();
        hotState.clear();
    }

    /**
     * 测试观察点：`revisions` 被调用的次数。
     *
     * @return 次数
     */
    public int revisionsCalls() {
        return revisionsCalls.get();
    }

    /**
     * 测试观察点：`read`（版本 + 值的合并读）被调用的次数。
     *
     * @return 次数
     */
    public int readCalls() {
        return readCalls.get();
    }

    /**
     * 测试观察点：`revisions` 累计请求的 key 数。
     *
     * @return key 数
     */
    public int revisionsKeys() {
        return revisionsKeys.get();
    }

    /**
     * 重置观察点。
     */
    public void resetCounters() {
        revisionsCalls.set(0);
        revisionsKeys.set(0);
        readCalls.set(0);
    }

    /**
     * 测试观察点：某 key 的过期时刻（{@link Long#MAX_VALUE} 表示不过期）。
     *
     * @param key key
     * @return 过期时刻（毫秒）
     */
    public long expireAtMillis(CacheKey key) {
        Entry<V> entry = data.get(key);
        return entry == null ? -1L : entry.expireAtMillis;
    }

    /**
     * 测试观察点：原始条目数（含已过期但尚未被清理的）。
     *
     * @return 条目数
     */
    public int size() {
        return data.size();
    }

    private Entry<V> live(CacheKey key) {
        Entry<V> entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(milliClock.getAsLong())) {
            data.remove(key, entry);
            return null;
        }
        return entry;
    }

    private long nextRevision(Entry<V> existing, long now) {
        long previous = existing == null || existing.expired(now) ? 0L : existing.revision;
        return Math.max(now, previous + 1L);
    }

    private long expireAt(Duration ttl, long now) {
        if (ttl == null || ttl.isZero()) {
            return Long.MAX_VALUE;
        }
        return now + ttl.toMillis();
    }

    /**
     * 内存条目。
     *
     * @param value          值；负副本为 {@code null}
     * @param negative       是否"已确认不存在"
     * @param revision       版本
     * @param expireAtMillis 过期时刻
     * @param <V>            值类型
     */
    private record Entry<V>(V value, boolean negative, long revision, long expireAtMillis) {

        boolean expired(long now) {
            return expireAtMillis != Long.MAX_VALUE && now - expireAtMillis >= 0L;
        }
    }

    /**
     * 双窗口计数。
     */
    private static final class Window {

        private long windowId = Long.MIN_VALUE;
        private long current;
        private long previous;
        private long lastActiveWindow = -1L;
        private long lastHotWindow = -1L;
        private long lowSince = -1L;

        void rotateTo(long target) {
            if (windowId == target) {
                return;
            }
            previous = windowId == target - 1L ? current : 0L;
            current = 0L;
            windowId = target;
        }
    }
}
