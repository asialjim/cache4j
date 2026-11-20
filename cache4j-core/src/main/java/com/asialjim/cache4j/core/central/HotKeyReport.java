package com.asialjim.cache4j.core.central;

/**
 * 热 key 上报的结果：<strong>只回传状态</strong>，不回传计数或 QPS。
 *
 * <p><strong>这是一条硬约束</strong>：中央的计数是"所有节点增量之和"。一旦把它回传并落到本地，
 * 本地下次上报时可能把"中央的累计值"当成"自己的增量"再报一次，形成重复放大；
 * 或者本地用中央数值覆盖自己的估计，导致 {@code localQps} 不再代表本进程。
 * 两个口径就此串味，之后再也分不清"到底是本进程热还是全局热"。</p>
 *
 * <p>所以上报方向只传"自上次上报以来的增量"，回传方向只传状态；
 * 两个时间戳字段仅用于排障（能看出中央多久没收到这个 key 的上报了）。</p>
 *
 * @param status           状态
 * @param lastActiveWindow 最后一次收到上报的窗口号（排障用）
 * @param lastHotWindow    最后一次达到全局热阈值的窗口号（排障用）
 * @author Asial Jim
 */
public record HotKeyReport(
        HotKeyStatus status,
        long lastActiveWindow,
        long lastHotWindow
) {

    /**
     * 中央未见过该 key 时的结果。
     *
     * @return 报告
     */
    public static HotKeyReport unknown() {
        return new HotKeyReport(HotKeyStatus.UNKNOWN, -1L, -1L);
    }
}
