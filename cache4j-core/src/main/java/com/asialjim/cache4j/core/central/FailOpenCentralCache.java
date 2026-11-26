package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.VersionedLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 中央缓存的<strong>故障降级</strong>装饰器：把"中央会失败"这件事关在中央层里。
 *
 * <p>编排层因此不需要在每个调用点上 {@code try/catch + if (failOpen) throw}——
 * 那会让"中央可能故障"泄漏成编排逻辑。这里统一处理：</p>
 *
 * <table border="1">
 *   <caption>失败时的降级语义</caption>
 *   <tr><th>方法</th><th>降级值</th><th>编排层的理解</th></tr>
 *   <tr><td>{@code lookupWithRevision}</td><td>{@code null}</td><td>当作未命中，去回源</td></tr>
 *   <tr><td>{@code putAndGetRevision}</td><td>{@code 0L}</td><td>没拿到版本 → 不写本地（避免"只有我能看到的新值"）</td></tr>
 *   <tr><td>{@code putNegativeAndGetRevision}</td><td>{@code 0L}</td><td>同上</td></tr>
 *   <tr><td>{@code putIfAbsentAndGetRevision}</td><td>{@code empty}</td><td>无法保证原子性 → 当作"没写成功"</td></tr>
 *   <tr><td>{@code revisions}</td><td>{@code null}</td><td><strong>本次对账放弃</strong>——不能返回空 map，那会被误判成"中央已全部删除"</td></tr>
 *   <tr><td>{@code evict} / {@code clear}</td><td>吞掉</td><td>本地照常清</td></tr>
 * </table>
 *
 * <p>{@code failOpen=false} 时<strong>不降级</strong>，异常原样抛出。因此本类只在
 * {@code failOpen=true} 时装配（见 {@code CentralCaches}）。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class FailOpenCentralCache<V> implements CentralCache<V> {

    private static final Logger LOG = LoggerFactory.getLogger(FailOpenCentralCache.class);

    private final CentralCache<V> delegate;

    public FailOpenCentralCache(CentralCache<V> delegate) {
        this.delegate = delegate;
    }

    @Override
    public VersionedLookup<V> lookupWithRevision(CacheKey key) {
        try {
            return delegate.lookupWithRevision(key);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-读-[{}]-{}", "统一中央缓存", key.masked(),
                    "中央读取失败，按未命中降级，原因=" + reason(e));
            return null;
        }
    }

    @Override
    public Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys) {
        try {
            return delegate.lookupAllWithRevision(keys);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-读-[{}]-{}", "统一中央缓存", "批量",
                    "中央批量读取失败，按全部未命中降级，原因=" + reason(e));
            return Map.of();
        }
    }

    @Override
    public long putAndGetRevision(CacheKey key, V value, Duration ttl) {
        try {
            return delegate.putAndGetRevision(key, value, ttl);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-写-[{}]-{}", "统一中央缓存", key.masked(),
                    "中央写失败，按 failOpen 降级（不写本地），原因=" + reason(e));
            return 0L;
        }
    }

    @Override
    public long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
        try {
            return delegate.putNegativeAndGetRevision(key, ttl);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-写-[{}]-{}", "统一中央缓存", key.masked(),
                    "中央写负副本失败，按 failOpen 降级，原因=" + reason(e));
            return 0L;
        }
    }

    @Override
    public OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl) {
        try {
            return delegate.putIfAbsentAndGetRevision(key, value, ttl);
        } catch (RuntimeException e) {
            // 无法保证原子性，不能静默当成"本地成功"
            LOG.warn("[{}]-写-[{}]-{}", "统一中央缓存", key.masked(),
                    "中央 putIfAbsent 失败，按 failOpen 降级（视为未写入），原因=" + reason(e));
            return OptionalLong.empty();
        }
    }

    @Override
    public Map<CacheKey, Long> revisions(Collection<CacheKey> keys) {
        try {
            return delegate.revisions(keys);
        } catch (RuntimeException e) {
            // 关键：不能返回空 map —— 那会被对账解读成"中央已不再持有这些 key"从而清空本地副本
            LOG.warn("[{}]-{}-{}", "统一本地缓存", "对账",
                    "中央版本读取失败，本次对账放弃，原因=" + reason(e));
            return null;
        }
    }

    @Override
    public void evict(CacheKey key) {
        try {
            delegate.evict(key);
        } catch (RuntimeException e) {
            LOG.warn("[{}]-写-[{}]-{}", "统一中央缓存", key.masked(),
                    "中央失效失败，按 failOpen 降级（本地照常清），原因=" + reason(e));
        }
    }

    // ------------------------------------------------------------------ 其余直接转发

    @Override
    public Map<CacheKey, Lookup<V>> lookupAll(Collection<CacheKey> keys) {
        return delegate.lookupAll(keys);
    }

    @Override
    public long revision(CacheKey key) {
        return delegate.revision(key);
    }

    @Override
    public boolean ping() {
        try {
            return delegate.ping();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public CentralCacheSpec spec() {
        return delegate.spec();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Lookup<V> lookup(CacheKey key) {
        return delegate.lookup(key);
    }

    @Override
    public boolean containsKey(CacheKey key) {
        return delegate.containsKey(key);
    }

    @Override
    public void put(CacheKey key, V value, Duration ttl) {
        delegate.put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(CacheKey key, V value, Duration ttl) {
        return delegate.putIfAbsent(key, value, ttl);
    }

    @Override
    public void clear() {
        try {
            delegate.clear();
        } catch (RuntimeException e) {
            LOG.warn("[{}]-{}-{}", "统一中央缓存", "清空",
                    "中央清空失败，按 failOpen 降级（本地照常清），原因=" + reason(e));
        }
    }

    private static String reason(RuntimeException e) {
        return e.getClass().getSimpleName() + ":" + e.getMessage();
    }
}
