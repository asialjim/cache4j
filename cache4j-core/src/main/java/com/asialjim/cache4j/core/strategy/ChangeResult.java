package com.asialjim.cache4j.core.strategy;

/**
 * 变更结果。
 *
 * @param outcome       结果状态
 * @param namespace     目标 namespace
 * @param configVersion 变更后的配置版本；{@code CONFLICT} 时为<strong>当前</strong>版本
 * @param current       变更后（或当前）的生效配置，便于调用方直接渲染
 * @param message       人话；{@code REJECTED} 时必须含具体字段与原因
 * @param requestId     幂等键回显
 * @author Asial Jim
 */
public record ChangeResult(
        Outcome outcome,
        String namespace,
        long configVersion,
        NamespacePolicy current,
        String message,
        String requestId
) {

    /**
     * 变更结果状态。
     */
    public enum Outcome {

        /**
         * 已持久化并生效。
         */
        APPLIED,

        /**
         * 数据源已写入成功，但写穿缓存与 clear 兜底都失败：本节点已生效，
         * 其它节点可能延迟到 {@code centralTtl} 才看到。必须告警并补偿。
         */
        APPLIED_STALE,

        /**
         * 目标配置与当前逐字段相同，未做任何写入。
         */
        NO_CHANGE,

        /**
         * 乐观锁冲突（配置版本不匹配）。
         */
        CONFLICT,

        /**
         * 校验失败或持久化失败，生效值不变。
         */
        REJECTED
    }

    /**
     * @return 是否属于"已生效"（含降级生效）
     */
    public boolean applied() {
        return outcome == Outcome.APPLIED || outcome == Outcome.APPLIED_STALE;
    }
}
