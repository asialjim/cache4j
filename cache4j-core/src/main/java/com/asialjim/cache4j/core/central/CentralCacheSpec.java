package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;

import java.time.Duration;

/**
 * 中央缓存配置（一条命名缓存的参数）。
 *
 * <p>命令超时<strong>不在本库配置</strong>：{@code cache4j-central} 不持有连接，没有执行超时的
 * 位置。Spring 用户由 {@code spring.data.redis.timeout} 控制；自实现 {@code RedisCommands}
 * 的用户自行决定。建议把缓存命令配成 200–500ms 快速失败，让 {@code failOpen} 尽早生效。</p>
 *
 * @param namespace       缓存名
 * @param ttl             默认 TTL；必须为正（或显式 {@code allowNoExpiry}）
 * @param nullTtl         空值缓存 TTL；{@code 0} 表示关闭
 * @param failOpen        编排层默认降级策略
 * @param ttlJitter       TTL 抖动比例（0–0.5）
 * @param allowNoExpiry   是否允许永不过期（默认 {@code false}）
 * @author Asial Jim
 */
public final class CentralCacheSpec {

    private final com.asialjim.cache4j.core.CacheStrategy strategy;

    private CentralCacheSpec(com.asialjim.cache4j.core.CacheStrategy strategy) {
        this.strategy = strategy;
    }

    public String namespace() {
        return strategy.namespace();
    }

    public Duration ttl() {
        return strategy.centralTtl();
    }

    public Duration nullTtl() {
        return strategy.centralNullTtl();
    }

    public boolean failOpen() {
        return strategy.failOpen();
    }

    public double ttlJitter() {
        return strategy.centralTtlJitter();
    }

    public boolean allowNoExpiry() {
        return strategy.allowNoExpiry();
    }

    /**
     * 从完整策略里取出中央侧部分（全局唯一实例可能同时含本地字段，这里只保留中央那几个）。
     *
     * @param strategy 完整策略
     * @return 中央规格
     */
    public static CentralCacheSpec of(com.asialjim.cache4j.core.CacheStrategy strategy) {
        return new CentralCacheSpec(strategy);
    }

    /**
     * 转成运行期策略（<strong>过渡用</strong>：本地侧字段取内建默认值）。
     *
     * @return 运行期策略
     */
    public com.asialjim.cache4j.core.CacheStrategy toStrategy() {
        return strategy;
    }

    /**
     * 构造构建器。
     *
     * @param namespace 缓存名
     * @return 构建器
     */
    public static Builder builder(String namespace) {
        return new Builder(namespace);
    }

    /**
     * @return 是否启用空值缓存
     */
    public boolean nullCachingEnabled() {
        return strategy.nullCachingEnabled();
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }

    /**
     * 构建器：默认 TTL 10 分钟、关闭空值缓存、{@code failOpen=true}、抖动 0.1。
     *
     * @author Asial Jim
     */
    public static final class Builder {

        private final String namespace;
        private Duration ttl = Duration.ofMinutes(10);
        private Duration nullTtl = Duration.ZERO;
        private boolean failOpen = true;
        private double ttlJitter = 0.1D;
        private boolean allowNoExpiry;

        private Builder(String namespace) {
            this.namespace = namespace;
        }

        /**
         * @param value 默认 TTL
         * @return this
         */
        public Builder ttl(Duration value) {
            this.ttl = value;
            return this;
        }

        /**
         * @param value 空值缓存 TTL；{@code ZERO} 表示关闭
         * @return this
         */
        public Builder nullTtl(Duration value) {
            this.nullTtl = value;
            return this;
        }

        /**
         * @param value 是否降级
         * @return this
         */
        public Builder failOpen(boolean value) {
            this.failOpen = value;
            return this;
        }

        /**
         * @param value TTL 抖动比例
         * @return this
         */
        public Builder ttlJitter(double value) {
            this.ttlJitter = value;
            return this;
        }

        /**
         * @param value 是否允许永不过期
         * @return this
         */
        public Builder allowNoExpiry(boolean value) {
            this.allowNoExpiry = value;
            return this;
        }

        /**
         * @return 不可变配置
         */
        public CentralCacheSpec build() {
            com.asialjim.cache4j.core.CacheStrategy base =
                    com.asialjim.cache4j.core.CacheStrategy.staticCache(namespace,
                            Math.max(1L, 1_000L), Duration.ofMinutes(1));
            return new CentralCacheSpec(base.withCentral(ttl, nullTtl == null ? Duration.ZERO : nullTtl,
                    failOpen));
        }
    }
}
