package com.asialjim.cache4j.core.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库门面：进程内生效配置的唯一权威。
 *
 * <p>部署边界：<strong>业务端只调用 {@link #find}/{@link #all}</strong>；
 * 变更入口 {@link CacheStrategyAdmin} 只在独立的管理服务里装配。</p>
 *
 * @author Asial Jim
 */
public interface CacheStrategyRepository {

    /**
     * 登记一条声明。
     *
     * <p><strong>仓库已有该 namespace 的配置时直接跳过</strong>：不登记、不覆盖、不改动已有信息
     * （仓库是权威，代码声明不得回写已存在的配置）。跳过是幂等行为，只记 debug 日志，不告警。</p>
     *
     * <p>仅当两个<strong>声明</strong>争抢同一个且仓库里还没有的 namespace 时，才抛冲突。</p>
     *
     * @param decl   声明
     * @param origin 来源（用于冲突报错）
     */
    void declare(CacheStrategyDecl decl, String origin);

    /**
     * 登记完毕：对每条声明解析出生效值（预装配集合 = 声明集合）。
     *
     * <p>不遍历数据源——数据源里有、声明里没有的记录不参与装配（孤儿）。</p>
     *
     * @return 解析出的 namespace 列表（保持声明顺序）
     */
    List<String> bootstrap();

    /**
     * @param namespace 命名空间
     * @return 三态结果
     */
    PolicyLookup find(String namespace);

    /**
     * @return 全部生效配置（保持声明顺序）
     */
    List<NamespacePolicy> all();

    /**
     * 便捷方法：把生效配置转成装配定义，直接交给 {@code Cache4j} 的装配器。
     *
     * @return 装配定义列表（保持声明顺序）
     */
    default List<CacheDefinition> definitions() {
        List<NamespacePolicy> policies = all();
        List<CacheDefinition> definitions = new ArrayList<>(policies.size());
        for (NamespacePolicy policy : policies) {
            definitions.add(policy.toDefinition());
        }
        return definitions;
    }
}
