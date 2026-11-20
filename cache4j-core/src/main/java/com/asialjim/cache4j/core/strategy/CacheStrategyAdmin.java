package com.asialjim.cache4j.core.strategy;

import java.util.Optional;

/**
 * 运维管理入口：<strong>只在独立的管理服务里装配</strong>，业务 JVM 一律不暴露。
 *
 * <p>库只提供动作，不引 web 依赖、不做鉴权；端点暴露与身份校验由使用方负责。</p>
 *
 * @author Asial Jim
 */
public interface CacheStrategyAdmin {

    /**
     * 分页查询（运维先列表、再进详情）。
     *
     * @param keyword 关键字（匹配 namespace / description，可为空）
     * @param page    页码（从 0 开始）
     * @param size    页大小
     * @return 分页视图
     */
    Page<StrategyView> page(String keyword, int page, int size);

    /**
     * 详情视图（管理面的详情页）。
     *
     * <p>与 {@link CacheStrategyRepository#find} 区分：那个返回三态解析结果，这个返回摊平后的运维视图。</p>
     *
     * @param namespace 命名空间
     * @return 详情视图
     */
    Optional<StrategyView> view(String namespace);

    /**
     * 提交变更。
     *
     * <p>流程：幂等检查 → 校验 → 与当前逐字段比较 → 乐观锁 → 试装配 → 持久化 → 原子提交 → 审计。
     * 任何一步失败都不改变生效值。</p>
     *
     * @param change 变更请求
     * @return 结果
     */
    ChangeResult change(StrategyChange change);
}
