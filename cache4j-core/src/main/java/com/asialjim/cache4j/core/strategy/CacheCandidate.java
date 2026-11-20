package com.asialjim.cache4j.core.strategy;

/**
 * 一条"已翻译"的候选声明：装配定义 + 来源。
 *
 * <p>来源（{@code origin}）形如 {@code com.acme.UserCacheDecls#USER_DETAIL (META-INF/services：…)}，
 * 用于去重与冲突报错——出问题时必须一眼看出是哪一行声明。</p>
 *
 * @param origin     来源标识
 * @param definition 装配定义
 * @author Asial Jim
 */
public record CacheCandidate(String origin, CacheDefinition definition) {

    /**
     * @return 缓存的 namespace
     */
    public String namespace() {
        return definition.namespace();
    }
}
