package com.asialjim.cache4j.core.strategy;

/**
 * 策略查询的三态结果。
 *
 * <p>用 {@code Optional} 无法区分"确实没配"和"读不到"——两者的处理完全不同：</p>
 * <ul>
 *   <li>{@link Absent}：数据源明确回答"没有这个 namespace" ⇒ 用声明 + 内建默认，并且可以缓存这个结论；</li>
 *   <li>{@link Unavailable}：数据源不可达/记录损坏 ⇒ 要告警，且<strong>不得</strong>把失败缓存成"不存在"。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public sealed interface PolicyLookup {

    /**
     * @return 找到的生效配置；其它状态返回 {@code null}
     */
    default NamespacePolicy policyOrNull() {
        return this instanceof Found found ? found.policy() : null;
    }

    /**
     * 找到了配置。
     *
     * @param policy 生效配置
     */
    record Found(NamespacePolicy policy) implements PolicyLookup {
    }

    /**
     * 明确没有配置。
     */
    record Absent() implements PolicyLookup {
    }

    /**
     * 读不到（不可达 / 损坏）。
     *
     * @param cause 原因
     */
    record Unavailable(Throwable cause) implements PolicyLookup {
    }
}
