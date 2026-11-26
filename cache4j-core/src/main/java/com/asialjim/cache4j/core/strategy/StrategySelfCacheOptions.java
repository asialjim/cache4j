package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.VersionSpec;
import com.asialjim.cache4j.core.CacheStrategy;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 仓库自身缓存的启动配置（<strong>改需重启</strong>）。
 *
 * <p>这些参数不能放进策略仓库本身——读策略要先读策略，自指。所以只能来自启动配置。
 * 它们决定自举缓存的构造参数，热改等于重建仓库自己。</p>
 *
 * <p><strong>策略固定 {@code STATIC}，禁止 {@code NONE} / {@code DYNAMIC}</strong>：</p>
 * <ul>
 *   <li>{@code NONE}：每次读策略都打中央；更糟的是中央抖动时仓库不可用，会连带业务无法装配；</li>
 *   <li>{@code DYNAMIC}：热准入判定要读的正是它自己在提供的参数，形成自指与先有鸡还是先有蛋；</li>
 *   <li>{@code STATIC} 是唯一自洽的选择：行为确定、有本地副本、无自指。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public final class StrategySelfCacheOptions {

    /**
     * 自举缓存的命名空间（与业务 namespace 隔离；{@code clear(ns)} 不得波及）。
     */
    public static final String SELF_NAMESPACE = "_stg_";

    /**
     * 本地条目上限默认值（最可能需调的项：namespace 上千时上调）。
     */
    public static final long DEFAULT_MAX_SIZE = 1_000L;

    /**
     * 本地副本存活默认值（策略极少变，本地可以活得久）。
     */
    public static final Duration DEFAULT_LOCAL_TTL = Duration.ofMinutes(30);

    /**
     * 中央副本存活默认值。
     */
    public static final Duration DEFAULT_CENTRAL_TTL = Duration.ofDays(3);

    /**
     * "该 namespace 没有策略"的负缓存时长。
     */
    public static final Duration DEFAULT_CENTRAL_NULL_TTL = Duration.ofMinutes(5);

    /**
     * 版本对账窗口默认值 —— <strong>它就是"变更在多节点上生效的最大延迟"</strong>，不建议调大。
     */
    public static final Duration DEFAULT_VERSION_CHECK_INTERVAL = Duration.ofSeconds(5);

    private final long maxSize;
    private final Duration localTtl;
    private final Duration centralTtl;
    private final Duration centralNullTtl;
    private final Duration versionCheckInterval;

    private StrategySelfCacheOptions(Builder builder) {
        this.maxSize = builder.maxSize;
        this.localTtl = builder.localTtl;
        this.centralTtl = builder.centralTtl;
        this.centralNullTtl = builder.centralNullTtl;
        this.versionCheckInterval = builder.versionCheckInterval;
    }

    /**
     * @return 全默认配置
     */
    public static StrategySelfCacheOptions defaults() {
        return builder().build();
    }

    /**
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 自举缓存的本地配置（策略固定 STATIC）
     */
    /**
     * 自举缓存的完整策略。
     *
     * @return 策略
     */
    public CacheStrategy cacheStrategy() {
        // 一次构造 + 一次中央侧覆盖：不在中途造出"只有半边有值"的中间策略
        VersionSpec version = new VersionSpec(
                versionCheckInterval, Duration.ofMillis(100), Duration.ofMillis(200), 1_000, 16);
        return CacheStrategy.staticCache(SELF_NAMESPACE, maxSize, localTtl, version)
                .withCentral(centralTtl, centralNullTtl, true);
    }

    /**
     * @return 版本对账窗口（= 跨节点生效最大延迟）
     */
    public Duration versionCheckInterval() {
        return versionCheckInterval;
    }

    /**
     * 启动日志用的一行描述。
     *
     * @return 描述
     */
    public String describe() {
        return "namespace=" + SELF_NAMESPACE + "，策略=" + LocalStrategy.STATIC
                + "，maxSize=" + maxSize + "，localTtl=" + localTtl
                + "，centralTtl=" + centralTtl + "，centralNullTtl=" + centralNullTtl
                + "，versionCheck=" + versionCheckInterval;
    }

    /**
     * 构建器。
     *
     * @author Asial Jim
     */
    public static final class Builder {

        private long maxSize = DEFAULT_MAX_SIZE;
        private Duration localTtl = DEFAULT_LOCAL_TTL;
        private Duration centralTtl = DEFAULT_CENTRAL_TTL;
        private Duration centralNullTtl = DEFAULT_CENTRAL_NULL_TTL;
        private Duration versionCheckInterval = DEFAULT_VERSION_CHECK_INTERVAL;

        /**
         * @param value 本地条目上限（namespace 上千时上调）
         * @return this
         */
        public Builder maxSize(long value) {
            if (value < 1L) {
                throw new IllegalArgumentException("maxSize 必须 >= 1：" + value);
            }
            this.maxSize = value;
            return this;
        }

        /**
         * @param value 本地副本存活
         * @return this
         */
        public Builder localTtl(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("localTtl 必须为正：" + value);
            }
            this.localTtl = value;
            return this;
        }

        /**
         * @param value 中央副本存活
         * @return this
         */
        public Builder centralTtl(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("centralTtl 必须为正：" + value);
            }
            this.centralTtl = value;
            return this;
        }

        /**
         * @param value 负缓存时长
         * @return this
         */
        public Builder centralNullTtl(Duration value) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("centralNullTtl 不能为负：" + value);
            }
            this.centralNullTtl = value;
            return this;
        }

        /**
         * @param value 版本对账窗口（= 跨节点生效最大延迟）
         * @return this
         */
        public Builder versionCheckInterval(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("versionCheckInterval 必须为正：" + value);
            }
            this.versionCheckInterval = value;
            return this;
        }

        /**
         * @return 不可变配置
         */
        public StrategySelfCacheOptions build() {
            return new StrategySelfCacheOptions(this);
        }
    }
}
