package com.asialjim.cache4j.strategy;

/**
 * 缓存快： 具有相同业务属性的缓存名称集合, 这些集合对应的缓存，具有相同的策略属性
 * 如：缓存过期时间，本地策略等
 * @author Asial Jim
 */
public interface CacheBlock {
    /**
     * @return 缓存命名空间（例如 {@code user:detail}）；允许 {@code [A-Za-z0-9._:-]}，长度 ≤ 64
     */
    String getNamespace();

    /**
     * @return 初始本地策略（<strong>声明值</strong>，可被策略仓库的运行期配置覆盖）
     */
    LocalStrategy getLocalStrategy();

    /**
     * @return 用途说明；<strong>必须非空</strong>，运维要据此判断这个缓存是干什么用的
     */
    String getDescription();
}
