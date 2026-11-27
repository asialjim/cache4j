package com.asialjim.cache4j;

import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Map;

/**
 * 热 key 探测器：把"与中央的热度交互"从本地实现里抽出去。
 *
 * <p>与 {@link Cache4jKeyVersionDetector} 同构——一个负责版本（<strong>读</strong>中央），
 * 一个负责热度（<strong>写</strong>中央并读回裁决）：</p>
 *
 * <table border="1">
 *   <caption>两个探测器的对称性</caption>
 *   <tr><th></th><th>{@link Cache4jKeyVersionDetector}</th><th>{@link Cache4jHotKeyDetector}</th></tr>
 *   <tr><td>单 key</td><td>{@code keyVersion}</td><td>{@code status}</td></tr>
 *   <tr><td>批量</td><td>{@code partitionVersion}</td><td>{@code report}</td></tr>
 *   <tr><td>数据流向</td><td>读中央</td><td>写中央 + 读回裁决</td></tr>
 * </table>
 *
 * <p>本地实现因此不需要知道"中央是 Redis 还是别的"——它只认识这个接口。
 * 实现方应当<strong>吞掉</strong>网络异常并返回 {@link HotKeyStatus#UNKNOWN}（或空 Map），
 * 让本地按"信息不足，保持现状"处理；上报失败由本地自己回填增量并退避。</p>
 *
 * <p>{@code coordination=LOCAL} 时本地不持有本接口的实现（成员为 {@code null}），
 * 降热完全由本地窗口判定。</p>
 *
 * @author Asial Jim
 */
public interface Cache4jHotKeyDetector {

    /**
     * 单 key 的中央裁决。
     *
     * @param key      key
     * @param strategy 缓存策略
     * @return 裁决；不确定时返回 {@link HotKeyStatus#UNKNOWN}
     */
    HotKeyStatus status(String key, CacheStrategy strategy);

    /**
     * 批量上报热度并取回裁决。
     *
     * @param batch    key → 本次上报的访问增量
     * @param strategy 缓存策略
     * @return key → 裁决；实现方应保证<strong>不返回 {@code null}</strong>（没有结论就返回空 Map）
     */
    Map<String, HotKeyStatus> report(Map<String, Long> batch, CacheStrategy strategy);
}
