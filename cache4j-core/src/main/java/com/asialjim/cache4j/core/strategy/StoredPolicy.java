package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * 策略的<strong>持久化形态</strong>：数据源里存的就是它。
 *
 * <p>与 {@link NamespacePolicy}（解析后的完整生效值）的区别：这里的字段<strong>允许缺省</strong>
 * （{@code null} 表示"运维没设置这一项，按内建默认"），解析时必须补齐。
 * 声明值（{@code declaredStrategy}）不在持久化形态里——那是代码里的事实，不是运维配置。</p>
 *
 * @param namespace        命名空间
 * @param description      运维改写的说明；{@code null} 表示沿用声明
 * @param strategy         生效策略
 * @param maxSize          本地条目上限；{@code null} 用内建默认
 * @param localTtl         本地非空副本存活；{@code null} 用内建默认
 * @param version          版本对账参数；{@code null} 用内建默认
 * @param dynamic          {@code DYNAMIC} 参数；{@code null} 用内建默认
 * @param centralTtl       中央非空存活；{@code null} 用内建默认
 * @param centralNullTtl   中央空值存活；{@code null} 用内建默认
 * @param failOpen         中央不可用是否降级；{@code null} 用内建默认
 * @param configVersion    配置版本号（运维乐观锁），由数据源生成
 * @param updatedAtMillis  最近一次变更时间
 * @param updatedBy        变更人（审计）
 * @param reason           变更原因（审计）
 * @author Asial Jim
 */
public record StoredPolicy(
        String namespace,
        String description,
        LocalStrategy strategy,
        Long maxSize,
        Duration localTtl,
        LocalCacheSpec.VersionSpec version,
        LocalCacheSpec.DynamicSpec dynamic,
        Duration centralTtl,
        Duration centralNullTtl,
        Boolean failOpen,
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason
) {
}
