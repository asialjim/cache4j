package com.asialjim.cache4j;

/**
 * 中央对某个 key 的热度裁决。
 *
 * <p>本地拿到 {@link #COOLING} 时必须降热——这是 {@code coordination=GLOBAL} 下的唯一降热依据：
 * 单节点看到的只是局部流量，只有中央汇总后才知道某个 key 是否真的不热了。</p>
 *
 * @author Asial Jim
 */
public enum HotKeyStatus {

    /**
     * 中央判定为热点：可以继续留在本地。
     */
    HOT,

    /**
     * 中央判定已降温：本地必须移除副本（并进入冷却期）。
     */
    COOLING,

    /**
     * 中央没有该 key 的记录，或本次上报失败。
     *
     * <p><strong>不降热</strong>：信息不足时宁可多留一会儿，也不要因为一次上报抖动把热 key 踢出本地。</p>
     */
    UNKNOWN
}
