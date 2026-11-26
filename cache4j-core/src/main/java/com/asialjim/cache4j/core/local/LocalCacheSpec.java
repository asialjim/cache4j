package com.asialjim.cache4j.core.local;

import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.Coordination;

import java.time.Duration;

/**
 * 本地配置的<strong>过渡壳</strong>：内部只有一份 {@link CacheStrategy}，所有取值转发过去。
 *
 * <p>它不再是"配置的来源"，只是旧调用面（{@code spec.ttl()} / {@code spec.version()} …）
 * 的适配层。运行期真正被引用的是 {@code CacheStrategy}——见 {@code Cache4j.cacheStrategy()}。
 * 这样做是为了让"删掉这个类"变成纯删除，而不是一次跨 60 个文件的类型手术。</p>
 *
 * <p>{@link VersionSpec} / {@link DynamicSpec} / {@link GlobalSpec} 同理：它们是
 * "构造 CacheStrategy 的参数分组"与"从策略派生的只读视图"，不再承担配置语义。</p>
 *
 * @author Asial Jim
 */
public final class LocalCacheSpec {

    /**
     * 未显式配置时的负副本除子。
     */
    public static final int DEFAULT_NULL_TTL_DIVISOR = CacheStrategy.DEFAULT_NULL_TTL_DIVISOR;

    private final CacheStrategy strategy;

    private LocalCacheSpec(CacheStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * @param strategy 完整策略
     * @return 本地配置视图
     */
    public static LocalCacheSpec of(CacheStrategy strategy) {
        return new LocalCacheSpec(strategy);
    }

    /**
     * @return 完整策略
     */
    public CacheStrategy toStrategy() {
        return strategy;
    }

    public String namespace() {
        return strategy.namespace();
    }

    public LocalStrategy strategy() {
        return strategy.localStrategy();
    }

    public long maxSize() {
        return strategy.localMaxSize();
    }

    public Duration ttl() {
        return strategy.localTtl();
    }

    public Duration nullTtl() {
        return strategy.localNullTtl();
    }

    public com.asialjim.cache4j.core.VersionSpec version() {
        return strategy.localStrategy() == LocalStrategy.NONE ? null
                : com.asialjim.cache4j.core.VersionSpec.of(strategy);
    }

    public com.asialjim.cache4j.core.DynamicSpec dynamic() {
        return strategy.isDynamic() ? com.asialjim.cache4j.core.DynamicSpec.of(strategy) : null;
    }

    /**
     * 完整校验：版本参数 + 热度参数（{@code NONE} 不校验——它没有这些概念）。
     *
     * @return 自身
     */
    public LocalCacheSpec validate() {
        if (strategy.localStrategy() == LocalStrategy.NONE) {
            return this;
        }
        version().validate();
        com.asialjim.cache4j.core.DynamicSpec d = dynamic();
        if (d != null) {
            d.validate(maxSize());
        }
        return this;
    }

    static com.asialjim.cache4j.core.CacheException invalid(String message) {
        return new com.asialjim.cache4j.core.CacheException(
                com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC, message);
    }

    /**
     * 派生负副本 TTL。
     *
     * @param localTtl       本地非空副本存活
     * @param centralNullTtl 中央空值存活
     * @param divisor        除子
     * @return 本地空值副本存活
     */
    public static Duration deriveNullTtl(Duration localTtl, Duration centralNullTtl, int divisor) {
        return CacheStrategy.deriveNullTtl(localTtl, centralNullTtl, divisor);
    }

    /**
     * {@code NONE}：本地不缓存。
     *
     * @param namespace 缓存名
     * @return 配置
     */
    public static LocalCacheSpec noLocal(String namespace) {
        return of(CacheStrategy.noLocal(namespace));
    }

    /**
     * {@code STATIC}。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl));
    }

    /**
     * {@code STATIC}，显式给版本参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl,
                                             com.asialjim.cache4j.core.VersionSpec version) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl, version));
    }

    /**
     * {@code STATIC}，显式给负副本 TTL 与版本参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL
     * @param version   版本参数
     * @return 配置
     */
    public static LocalCacheSpec staticCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                             com.asialjim.cache4j.core.VersionSpec version) {
        return of(CacheStrategy.staticCache(namespace, maxSize, ttl, nullTtl, version));
    }

    /**
     * {@code DYNAMIC}。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, com.asialjim.cache4j.core.DynamicSpec dynamic) {
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, dynamic));
    }

    /**
     * {@code DYNAMIC}，显式给版本参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl,
                                              com.asialjim.cache4j.core.VersionSpec version, com.asialjim.cache4j.core.DynamicSpec dynamic) {
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, version, dynamic));
    }

    /**
     * {@code DYNAMIC}，显式给负副本 TTL、版本参数与热度参数。
     *
     * @param namespace 缓存名
     * @param maxSize   条目上限
     * @param ttl       本地 TTL
     * @param nullTtl   负副本 TTL
     * @param version   版本参数
     * @param dynamic   热度参数
     * @return 配置
     */
    public static LocalCacheSpec dynamicCache(String namespace, long maxSize, Duration ttl, Duration nullTtl,
                                              com.asialjim.cache4j.core.VersionSpec version, com.asialjim.cache4j.core.DynamicSpec dynamic) {
        return of(CacheStrategy.dynamicCache(namespace, maxSize, ttl, nullTtl, version, dynamic));
    }
}
