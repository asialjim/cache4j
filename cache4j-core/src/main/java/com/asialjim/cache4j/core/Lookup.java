package com.asialjim.cache4j.core;

import java.util.Objects;
import java.util.Optional;

/**
 * 缓存读取结果：区分「有值」「已确认不存在」「没有缓存」三种状态。
 *
 * <p>{@link Optional} 无法区分"没有缓存"与"缓存了 null"，而防穿透又必须区分这两者，
 * 因此核心统一使用三态结果。语义如下：</p>
 *
 * <table border="1">
 *   <caption>三态语义</caption>
 *   <tr><th>结果</th><th>含义</th><th>是否回源</th></tr>
 *   <tr><td>{@link Hit}</td><td>有值</td><td>否</td></tr>
 *   <tr><td>{@link Negative}</td><td>已确认不存在，且负缓存未过期</td><td>否</td></tr>
 *   <tr><td>{@link Miss}</td><td>没有可用缓存</td><td>是</td></tr>
 * </table>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public sealed interface Lookup<V> permits Lookup.Hit, Lookup.Negative, Lookup.Miss {

    /**
     * 正命中：缓存中存在可用的值。
     *
     * @param value 命中值，不允许为 {@code null}
     * @param <V>   值类型
     */
    record Hit<V>(V value) implements Lookup<V> {
        public Hit {
            Objects.requireNonNull(value, "命中值不允许为 null");
        }
    }

    /**
     * 负命中：明确知道数据不存在，且这个"不存在"仍在有效期内。
     */
    record Negative<V>() implements Lookup<V> {
    }

    /**
     * 未命中：没有可用缓存，需要回源。
     */
    record Miss<V>() implements Lookup<V> {
    }

    /**
     * 构造正命中结果。
     *
     * @param value 命中值
     * @param <V>   值类型
     * @return 正命中
     */
    static <V> Lookup<V> hit(V value) {
        return new Hit<>(value);
    }

    /**
     * 构造负命中结果。
     *
     * @param <V> 值类型
     * @return 负命中
     */
    static <V> Lookup<V> negative() {
        return new Negative<>();
    }

    /**
     * 构造未命中结果。
     *
     * @param <V> 值类型
     * @return 未命中
     */
    static <V> Lookup<V> miss() {
        return new Miss<>();
    }

    /**
     * @return 是否正命中
     */
    default boolean isHit() {
        return this instanceof Hit<?>;
    }

    /**
     * @return 是否负命中
     */
    default boolean isNegative() {
        return this instanceof Negative<?>;
    }

    /**
     * @return 是否未命中
     */
    default boolean isMiss() {
        return this instanceof Miss<?>;
    }

    /**
     * 正命中返回值，其余状态返回空 {@link Optional}。
     *
     * @return 值的 {@link Optional} 视图
     */
    @SuppressWarnings("unchecked")
    default Optional<V> optional() {
        return this instanceof Hit<?> hit ? Optional.of(((Hit<V>) hit).value()) : Optional.empty();
    }
}
