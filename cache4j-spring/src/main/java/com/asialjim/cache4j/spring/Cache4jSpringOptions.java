package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.KeyEncoder;
import com.asialjim.cache4j.core.KeyEncoders;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.local.HashMapLocalCacheProvider;
import com.asialjim.cache4j.local.LocalCacheProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Spring 集成配置（<strong>不依赖 Spring Boot</strong>：没有 {@code @ConfigurationProperties}、
 * 没有自动装配注册文件，全部由代码显式构造）。
 *
 * <p>YAML / properties 绑定是"框架（Boot）"的职责，留给后续 {@code cache4j-starter}；
 * 本模块只接受一个明确的、可测试的配置对象。</p>
 *
 * @author Asial Jim
 */
public final class Cache4jSpringOptions {

    private final boolean enabled;
    private final KeyEncoder keyEncoder;
    private final LocalCacheProvider localProvider;
    private final CentralCacheProvider centralProvider;
    private final boolean ownershipOfCentralProvider;
    private final boolean statsEnabled;
    private final List<CacheDefinition> definitions;

    private Cache4jSpringOptions(Builder builder) {
        this.enabled = builder.enabled;
        this.keyEncoder = builder.keyEncoder;
        this.localProvider = builder.localProvider;
        this.centralProvider = builder.centralProvider;
        this.ownershipOfCentralProvider = builder.ownershipOfCentralProvider;
        this.statsEnabled = builder.statsEnabled;
        this.definitions = List.copyOf(builder.definitions);
    }

    /**
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 是否启用
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @return key 编码器
     */
    public KeyEncoder keyEncoder() {
        return keyEncoder;
    }

    /**
     * @return 本地缓存 provider
     */
    public LocalCacheProvider localProvider() {
        return localProvider;
    }

    /**
     * @return 中央缓存 provider
     */
    public CentralCacheProvider centralProvider() {
        return centralProvider;
    }

    /**
     * @return 关闭时是否由本模块负责关闭中央 provider
     */
    public boolean ownsCentralProvider() {
        return ownershipOfCentralProvider;
    }

    /**
     * @return 是否启用统计
     */
    public boolean statsEnabled() {
        return statsEnabled;
    }

    /**
     * @return 全部缓存定义（顺序保持）
     */
    public List<CacheDefinition> definitions() {
        return definitions;
    }

    /**
     * 构建器。
     *
     * @author Asial Jim
     */
    public static final class Builder {

        private boolean enabled = true;
        private KeyEncoder keyEncoder = KeyEncoders.canonical();
        private LocalCacheProvider localProvider = HashMapLocalCacheProvider.INSTANCE;
        private CentralCacheProvider centralProvider;
        private boolean ownershipOfCentralProvider = true;
        private boolean statsEnabled = true;
        private final List<CacheDefinition> definitions = new ArrayList<>();

        /**
         * @param value 是否启用
         * @return this
         */
        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        /**
         * @param value key 编码器
         * @return this
         */
        public Builder keyEncoder(KeyEncoder value) {
            this.keyEncoder = Objects.requireNonNull(value, "keyEncoder");
            return this;
        }

        /**
         * @param value 本地 provider（默认零依赖 HashMap 实现）
         * @return this
         */
        public Builder localProvider(LocalCacheProvider value) {
            this.localProvider = Objects.requireNonNull(value, "localProvider");
            return this;
        }

        /**
         * @param value            中央 provider（必填）
         * @param ownsWhenClosing  关闭时是否交由本模块关闭
         * @return this
         */
        public Builder centralProvider(CentralCacheProvider value, boolean ownsWhenClosing) {
            this.centralProvider = Objects.requireNonNull(value, "centralProvider");
            this.ownershipOfCentralProvider = ownsWhenClosing;
            return this;
        }

        /**
         * @param value 是否启用统计
         * @return this
         */
        public Builder statsEnabled(boolean value) {
            this.statsEnabled = value;
            return this;
        }

        /**
         * 追加一条缓存定义。
         *
         * @param definition 定义
         * @return this
         */
        public Builder cache(CacheDefinition definition) {
            this.definitions.add(Objects.requireNonNull(definition, "definition"));
            return this;
        }

        /**
         * 追加多条缓存定义。
         *
         * @param values 定义
         * @return this
         */
        public Builder caches(List<CacheDefinition> values) {
            if (values != null) {
                values.forEach(this::cache);
            }
            return this;
        }

        /**
         * @return 不可变配置
         */
        public Cache4jSpringOptions build() {
            if (enabled && centralProvider == null) {
                throw new IllegalStateException("必须提供 CentralCacheProvider（内存或 Redis 适配）");
            }
            return new Cache4jSpringOptions(this);
        }

        /**
         * @return 当前已收集的定义（只读）
         */
        public List<CacheDefinition> definitions() {
            return Collections.unmodifiableList(definitions);
        }
    }
}
