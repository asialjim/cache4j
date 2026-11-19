package com.asialjim.cache4j.core.central;

/**
 * 中央热 key 状态。
 *
 * @author Asial Jim
 */
public enum HotKeyStatus {

    /**
     * 集群窗口频次达到升热阈值：升热或续期。
     */
    HOT,

    /**
     * 有上报但未达升热阈值：保持现状，不降热。
     */
    ACTIVE,

    /**
     * 连续低活动或长时间无上报：<strong>唯一</strong>的降热依据。
     */
    COOLING,

    /**
     * 中央从未收到该 key 的上报：保持现状，等待下次上报。
     */
    UNKNOWN
}
