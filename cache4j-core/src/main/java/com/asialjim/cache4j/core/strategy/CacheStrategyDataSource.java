package com.asialjim.cache4j.core.strategy;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 策略的<strong>权威持久值</strong>：开发者实现这个接口即可接入任意存储。
 *
 * <p><strong>它不对接缓存体系</strong>：不感知版本窗口、不感知两层结构、不做缓存（缓存是门面的事）。
 * 只回答两个问题："现在的权威配置是什么"、"把新配置写进去"。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>{@code load} 未命中返回 empty，<strong>不得</strong>用异常表示"没有"；异常一律表示"不可用"；</li>
 *   <li>{@code query} 的过滤与分页必须在<strong>存储侧</strong>完成，不得全量拉回内存分页；</li>
 *   <li>{@code configVersion} 由数据源生成且单调递增；门面只比较、不自增；</li>
 *   <li>实现里<strong>不要</strong>再加一层 LRU/TTL 缓存；</li>
 *   <li>不支持 CAS 的实现可以让 {@code update} 退化为 last-write-wins（照常返回新版本号）。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public interface CacheStrategyDataSource {

    /**
     * @param namespace 命名空间
     * @return 该 namespace 的记录；不存在返回 empty（不是错误）
     */
    Optional<StoredPolicy> load(String namespace);

    /**
     * 分页查询（可模糊）：<strong>运维列表页专用</strong>。
     *
     * <p>必须由数据源<strong>在存储侧</strong>完成过滤与分页（SQL {@code WHERE ... LIMIT}、
     * Redis {@code SCAN} + {@code MATCH} 等），不能把全部记录拉回调用方再内存分页——
     * namespace 上千时那样等于每次翻页都做一次全量传输。</p>
     *
     * <p>启动装配<strong>不依赖</strong>本方法：预装配集合由声明决定。</p>
     *
     * @param keyword 模糊关键字（匹配 namespace / description），空表示不过滤
     * @param page    页码（从 0 开始）
     * @param size    页大小
     * @return 分页结果；{@code total} 必须是<strong>满足条件的总数</strong>，不是当前页条数
     */
    Page<StoredPolicy> query(String keyword, int page, int size);

    /**
     * 初始注册：写一条新记录，<strong>已存在则返回 false，绝不覆盖</strong>。
     *
     * @param policy 配置
     * @return {@code true} = 新建成功；{@code false} = 已存在（未改动任何数据）
     */
    boolean register(StoredPolicy policy);

    /**
     * 运维变更：乐观锁更新。
     *
     * @param policy                  新配置
     * @param expectedConfigVersion   期望的当前版本号
     * @return 新的版本号；版本冲突返回 empty
     */
    OptionalLong update(StoredPolicy policy, long expectedConfigVersion);

    /**
     * 数据源是否支持乐观锁 CAS（条件更新）。
     *
     * <p>不支持的实现可以让 {@link #update} 退化为 last-write-wins；此时运维视图里
     * {@code casSupported=false}，提示"并发修改同一 namespace 不会报冲突，请自行避免"。</p>
     *
     * @return 是否支持 CAS
     */
    default boolean supportsCas() {
        return true;
    }
}
