package com.asialjim.cache4j.core.strategy;

/**
 * 一次变更请求（管理服务专用）。
 *
 * <p>传<strong>完整</strong> {@link NamespacePolicy} 而不是字段补丁：运维的实操路径是
 * "列表/模糊分页 → 点进详情 → 改几项 → 提交"，此时手里已有完整对象；强类型没有字符串键拼写问题，
 * 想恢复内建默认直接把默认值填进去即可。</p>
 *
 * @param requestId         幂等键，必填；同一键重复提交返回首次结果
 * @param operator          操作人（审计）；鉴权由调用方完成
 * @param reason            变更原因（审计）
 * @param requestedAtMillis 请求时间（可拒绝过期请求）
 * @param policy            目标配置（完整对象）
 * @author Asial Jim
 */
public record StrategyChange(
        String requestId,
        String operator,
        String reason,
        long requestedAtMillis,
        NamespacePolicy policy
) {

    /**
     * @return 目标 namespace
     */
    public String namespace() {
        return policy == null ? null : policy.namespace();
    }
}
