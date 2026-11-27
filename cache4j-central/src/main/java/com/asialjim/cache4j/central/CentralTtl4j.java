package com.asialjim.cache4j.central;

import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 中央副本的存活时长计算。
 *
 * <p>这是 {@link CacheStrategy} 里三个 central 专属参数的<strong>唯一消费方</strong>：
 * {@code centralTtl}、{@code centralNullTtl}、{@code centralTtlJitter}。</p>
 *
 * <p>算出来的永远是<strong>秒数</strong>，交给存储层去设置真正的过期——
 * 用什么机制（{@code EXPIRE}、TTL 字段、还是别的）不归这里管。</p>
 *
 * @author Asial Jim
 */
public final class CentralTtl4j {

    /**
     * 不设过期时返回的值。
     *
     * <p>等价于 {@code allowNoExpiry=true}：存储层应当写入一条不会自动消失的条目。</p>
     */
    public static final long NO_EXPIRY = -1L;

    private CentralTtl4j() {
    }

    /**
     * 计算一条中央副本的存活时长。
     *
     * <p>正副本用 {@code centralTtl}，负副本（{@code value == null}）用 {@code centralNullTtl}。
     * 两者分开是必须的：空值代表"确认不存在"，而它随时可能变成存在
     * （新用户注册、商品上架），所以空值的寿命要显著短于正副本。</p>
     *
     * @param value    值；{@code null} 表示负副本
     * @param strategy 缓存策略
     * @return 秒数；{@link #NO_EXPIRY} 表示不设过期
     */
    public static long ttlSeconds(Object value, CacheStrategy strategy) {
        long base = value == null
                ? longOf(strategy.centralNullTtl(), 0L)
                : longOf(strategy.centralTtl(), 0L);
        if (base <= 0L) {
            return NO_EXPIRY;
        }
        return applyJitter(base, strategy);
    }

    /**
     * 判断这条副本是不是负副本。
     *
     * @param value 值
     * @return {@code true} 表示"确认不存在"
     */
    public static boolean negative(Object value) {
        return value == null;
    }

    /**
     * 给基础 TTL 加上随机抖动。
     *
     * <p>抖动的用途是防雪崩：大批 key 常在同一时刻被写入（启动预热、批量刷新），
     * 没有抖动它们会在同一时刻集体过期，把流量瞬间全砸回数据源。</p>
     *
     * <p>只往<strong>长了</strong>偏，不会短于配置值——运维配了 2 小时却 100 分钟就过期，
     * 会让人怀疑配置没生效。</p>
     *
     * @param baseSeconds 基础 TTL（秒）
     * @param strategy    缓存策略
     * @return 加抖动后的 TTL（秒）
     */
    public static long applyJitter(long baseSeconds, CacheStrategy strategy) {
        if (baseSeconds <= 0L) {
            return baseSeconds;
        }
        double jitter = doubleOf(strategy.centralTtlJitter(), 0D);
        if (jitter <= 0D) {
            return baseSeconds;
        }
        double factor = Math.max(0D, Math.min(1D, jitter));
        return baseSeconds + (long) (baseSeconds * factor * ThreadLocalRandom.current().nextDouble());
    }

    // ------------------------------------------------------------------ Supplier 取值（可空兜底）

    private static long longOf(Supplier<Long> supplier, long fallback) {
        return Optional.ofNullable(supplier).map(Supplier::get).orElse(fallback);
    }

    private static double doubleOf(Supplier<Double> supplier, double fallback) {
        return Optional.ofNullable(supplier).map(Supplier::get).orElse(fallback);
    }
}
