package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.StrategyCache4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Spring {@link Cache} 到 {@link StrategyCache4j} 的桥接。
 *
 * <h2>语义映射</h2>
 * <table border="1">
 *   <caption>Spring ↔ cache4j</caption>
 *   <tr><th>Spring</th><th>cache4j</th></tr>
 *   <tr><td>{@code get(key)}</td>
 *       <td>{@code lookup} → {@code Hit} 返回包装；{@code Negative} / {@code Miss} 都返回 {@code null}</td></tr>
 *   <tr><td>{@code get(key, type)}</td>
 *       <td>命中后检查类型，不匹配抛 {@link IllegalStateException}（与 Spring 语义一致）</td></tr>
 *   <tr><td>{@code get(key, Callable)}</td>
 *       <td>{@code get(key, Supplier)}——单飞与回源交给下层，本类只做异常翻译</td></tr>
 *   <tr><td>{@code put} / {@code evict} / {@code clear}</td><td>同名方法</td></tr>
 *   <tr><td>{@code putIfAbsent}</td>
 *       <td>{@code lookup} + {@code put}，与 Spring 默认实现同语义（<strong>非原子</strong>）</td></tr>
 * </table>
 *
 * <h2>负命中为什么返回 {@code null}</h2>
 * <p>Spring 的 {@link Cache} 只有两态：有值 / 没值。{@code Negative}（确认不存在）
 * 对 {@code @Cacheable} 来说就是"没有可用值"，必须回源——这正是负副本要防的穿透场景：
 * 回源一次、把"不存在"记下来，短时间内不再打数据源。<strong>回源仍会发生，
 * 只是被负副本挡住了对中央/数据源的重复访问。</strong></p>
 *
 * <h2>{@code putIfAbsent} 为什么不是原子的</h2>
 * <p>Spring 自己的默认实现就是 {@code get} + {@code put}，也没有并发保证
 * （见 {@link Cache#putIfAbsent}）。要严格原子需要 Redis 的 {@code SET NX} 那类原语，
 * 而中央缓存抽象里没有暴露它。这里保持与 Spring 一致，不做超出契约的承诺。</p>
 *
 * @author Asial Jim
 */
@SuppressWarnings({"unchecked", "NullableProblems"})
public final class Cache4jCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(Cache4jCache.class);

    private final StrategyCache4j<Object, Object> delegate;

    /**
     * @param delegate 二级缓存门面（已绑定某个 {@code CacheStrategy}）
     */
    public Cache4jCache(StrategyCache4j<Object, Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getCacheStrategy().namespace();
    }

    @Override
    public Object getNativeCache() {
        return delegate;
    }

    @Override
    public ValueWrapper get(Object key) {
        Lookup<Object> lookup = delegate.lookup(key);
        if (!lookup.isHit()) {
            // Negative 与 Miss 对 Spring 都是"没有可用值"
            return null;
        }
        Object value = lookup.optional().orElse(null);
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        Lookup<Object> lookup = delegate.lookup(key);
        if (!lookup.isHit()) {
            return null;
        }
        Object value = lookup.optional().orElse(null);
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "缓存 [" + getName() + "] 中 key " + key + " 的值类型为 "
                            + value.getClass().getName() + "，与期望的 " + type.getName() + " 不匹配");
        }
        return (T) value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object value = delegate.get(key, new CallableSupplier<>(key, valueLoader));
        //noinspection unchecked
        return (T) value;
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        Lookup<Object> existing = delegate.lookup(key);
        if (!existing.isMiss()) {
            Object current = existing.optional().orElse(null);
            return current == null ? null : new SimpleValueWrapper(current);
        }
        delegate.put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        // 清空 namespace 是合法业务操作（@CacheEvict 不给 key 就是它），
        // 但影响面是整个 namespace，日志留一条便于事后定位
        log.debug("[spring-cache]-clear namespace={}", getName());
        delegate.clear();
    }

    /**
     * @return 底层门面，便于业务取统计或做高级操作
     */
    public StrategyCache4j<Object, Object> unwrap() {
        return delegate;
    }

    /**
     * 把 Spring 的 {@link Callable} 适配成 {@link Supplier}。
     *
     * <p>{@code Supplier} 不能抛受检异常，而 {@code Callable} 可以。翻译方式与 Spring
     * 自己的约定一致：受检异常包成 {@link ValueRetrievalException}。</p>
     */
    private record CallableSupplier<T>(Object key, Callable<T> callable) implements Supplier<Object> {

        @Override
        public Object get() {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new org.springframework.cache.Cache.ValueRetrievalException(key, callable, e);
            }
        }
    }
}
