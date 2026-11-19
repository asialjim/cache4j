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
public record CentralCacheSpec(
        String namespace,
        Duration ttl,
        Duration nullTtl,
        boolean failOpen,
        double ttlJitter,
        boolean allowNoExpiry
) {

    /**
     * 紧凑构造器：基础校验。
     */
    public CentralCacheSpec {
        if (namespace == null || namespace.isBlank()) {
            throw invalid("namespace 不能为空");
        }
        if (ttl == null || ttl.isNegative()) {
            throw invalid("中央 ttl 不能为负：" + ttl);
        }
        if (ttl.isZero() && !allowNoExpiry) {
            throw invalid("中央缓存不允许无 TTL；如确需永不过期请显式设置 allowNoExpiry=true");
        }
        if (nullTtl == null || nullTtl.isNegative()) {
            throw invalid("nullTtl 不能为负：" + nullTtl);
        }
        if (ttlJitter < 0D || ttlJitter > 0.5D) {
            throw invalid("ttlJitter 必须在 [0, 0.5]：" + ttlJitter);
        }
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
        return nullTtl != null && !nullTtl.isZero();
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
            return new CentralCacheSpec(namespace, ttl, nullTtl, failOpen, ttlJitter, allowNoExpiry);
        }
    }
}
