package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.CacheLoader;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

/**
 * Spring {@link Cache} 到 {@link TwoLevelCache} 的桥接。
 *
 * <p>语义映射：</p>
 * <table border="1">
 *   <caption>映射</caption>
 *   <tr><th>Spring</th><th>cache4j</th></tr>
 *   <tr><td>{@code get(key)}</td><td>{@code lookup} → {@code Hit} 返回包装；{@code Negative}/{@code Miss} 返回 null</td></tr>
 *   <tr><td>{@code get(key, type)}</td><td>命中后做类型检查，不匹配抛 {@link IllegalStateException}（与 Spring 语义一致）</td></tr>
 *   <tr><td>{@code get(key, Callable)}</td><td>{@code lookup(key, loader)}，享受单飞（同 key 并发只执行一次）</td></tr>
 *   <tr><td>{@code put} / {@code evict} / {@code clear}</td><td>同名方法</td></tr>
 *   <tr><td>{@code putIfAbsent}</td><td>写入成功返回 null；已存在时返回现有值（Spring 语义）</td></tr>
 * </table>
 *
 * @author Asial Jim
 */
public final class SpringCacheAdapter implements Cache {

    private final TwoLevelCache<Object, Object> delegate;

    /**
     * @param delegate 二级缓存门面
     */
    public SpringCacheAdapter(TwoLevelCache<Object, Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.name();
    }

    @Override
    public Object getNativeCache() {
        return delegate;
    }

    @Override
    public ValueWrapper get(Object key) {
        Lookup<Object> lookup = delegate.lookup(key);
        return lookup.isHit() ? new SimpleValueWrapper(lookup.optional().orElseThrow()) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        Lookup<Object> lookup = delegate.lookup(key);
        if (!lookup.isHit()) {
            return null;
        }
        Object value = lookup.optional().orElseThrow();
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "缓存 [" + getName() + "] 中 key " + key + " 的值类型为 "
                            + value.getClass().getName() + "，与期望的 " + type.getName() + " 不匹配");
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Lookup<Object> lookup = delegate.lookup(key, new CallableLoader<>(valueLoader));
        return lookup.isHit() ? (T) lookup.optional().orElseThrow() : null;
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value, null);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        boolean stored = delegate.putIfAbsent(key, value, null);
        if (stored) {
            return null;
        }
        Lookup<Object> existing = delegate.lookup(key);
        return existing.isHit() ? new SimpleValueWrapper(existing.optional().orElseThrow()) : null;
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /**
     * @return 底层门面（便于业务做高级操作 / 取统计）
     */
    public TwoLevelCache<Object, Object> unwrap() {
        return delegate;
    }

    /**
     * 把 Spring 的 {@link Callable} 包成 {@link CacheLoader}；异常转成 Spring 的
     * {@link ValueRetrievalException} 语义（保持 Spring 行为一致）。
     *
     * @param callable 业务回源
     * @param <T>      值类型
     */
    private record CallableLoader<T>(Callable<T> callable) implements CacheLoader<Object, Object> {

        @Override
        public Object load(Object key) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ValueRetrievalException(key, callable, e);
            }
        }
    }
}
