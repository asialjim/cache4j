package com.asialjim.cache4j.core.central;

/**
 * 热 key 上报的结果：状态 + 全局窗口观测值。
 *
 * @param status           状态
 * @param windowCount      当前 + 上一窗口的集群合计频次
 * @param lastActiveWindow 最后一次收到上报的窗口号
 * @param lastHotWindow    最后一次达到全局热阈值的窗口号
 * @author Asial Jim
 */
public record HotKeyReport(
        HotKeyStatus status,
        long windowCount,
        long lastActiveWindow,
        long lastHotWindow
) {

    /**
     * 中央未见过该 key 时的结果。
     *
     * @return 报告
     */
    public static HotKeyReport unknown() {
        return new HotKeyReport(HotKeyStatus.UNKNOWN, 0L, -1L, -1L);
    }
}
