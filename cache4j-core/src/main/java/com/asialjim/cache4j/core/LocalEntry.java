package com.asialjim.cache4j.core;

/**
 * 本地副本：值 + 该 key 的版本 + 条目种类。
 *
 * <p>本地 key 就是 {@link CacheKey}，版本不进 key。失效是<strong>按 key</strong> 的：批量对账发现
 * 版本不一致（或中央已无该 field）就清除该条，不存在"整代切换"，也没有旧代条目待回收。</p>
 *
 * @param value    真实值；{@code kind == NEGATIVE} 时为 {@code null}
 * @param revision 该 key 级版本（中央时钟时间戳）
 * @param kind     条目种类
 * @param <V>      值类型
 * @author Asial Jim
 */
public record LocalEntry<V>(V value, long revision, LocalEntry.Kind kind) {

    /**
     * 条目种类。
     */
    public enum Kind {
        /**
         * 真实值。
         */
        VALUE,
        /**
         * 已确认不存在（仅当 {@code nullTtl > 0} 时出现）。
         */
        NEGATIVE
    }

    /**
     * 紧凑构造器：真值不允许为 {@code null}，负副本必须是 {@code null}。
     */
    public LocalEntry {
        if (kind == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "LocalEntry.kind 不能为空");
        }
        if (kind == Kind.VALUE && value == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "VALUE 条目的值不能为 null");
        }
        if (kind == Kind.NEGATIVE && value != null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "NEGATIVE 条目的值必须为 null");
        }
    }

    /**
     * 构造真值副本。
     *
     * @param value    值
     * @param revision 版本
     * @param <V>      值类型
     * @return 副本
     */
    public static <V> LocalEntry<V> hit(V value, long revision) {
        return new LocalEntry<>(value, revision, Kind.VALUE);
    }

    /**
     * 构造"已确认不存在"副本。
     *
     * @param revision 版本
     * @param <V>      值类型
     * @return 副本
     */
    public static <V> LocalEntry<V> negative(long revision) {
        return new LocalEntry<>(null, revision, Kind.NEGATIVE);
    }

    /**
     * @return 是否"已确认不存在"副本
     */
    public boolean isNegative() {
        return kind == Kind.NEGATIVE;
    }
}
