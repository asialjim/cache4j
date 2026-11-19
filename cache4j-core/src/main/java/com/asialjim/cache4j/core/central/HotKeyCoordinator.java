package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.CacheKey;

import java.util.Map;

/**
 * 跨节点热 key 协调 SPI（由中央侧实现）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>本地升热<strong>立即生效</strong>，不等中央确认；</li>
 *   <li>本地不升热 ≠ 不热：由本 SPI 做补偿查询；</li>
 *   <li>降热只认中央返回 {@link HotKeyStatus#COOLING}；</li>
 *   <li>没有访问就没有上报与查询（所有调用都由访问事件驱动）；</li>
 *   <li>{@code delta == 0} 是合法的"纯状态查询"。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public interface HotKeyCoordinator extends AutoCloseable {

    /**
     * 上报增量并查询状态。
     *
     * @param namespace 缓存名
     * @param key       key
     * @param delta     增量；{@code 0} 表示纯查询
     * @return 报告
     */
    HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta);

    /**
     * 批量上报 + 查询：一次网络往返处理一个分区的 key。
     *
     * @param namespace 缓存名
     * @param deltas    key → 增量（{@code 0} 表示纯查询）
     * @return key → 报告；缺失项视作 {@link HotKeyStatus#UNKNOWN}
     */
    Map<CacheKey, HotKeyReport> reportAndStatusAll(String namespace, Map<CacheKey, Long> deltas);

    @Override
    void close();
}
