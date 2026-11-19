package com.asialjim.cache4j.core;

/**
 * 缓存统计：分三层，<strong>互不重叠、绝不相加</strong>。
 *
 * <ol>
 *   <li><b>审计层</b>：业务读的漏斗，回答"缓存有没有用"。只有编排门面 {@code TwoLevelCache} 记账，
 *       存储实现一律返回 {@code 0}；</li>
 *   <li><b>控制层</b>：机制健康度（热 key / 版本对账），回答"参数对不对"；</li>
 *   <li><b>诊断层</b>：存储自身指标，回答"哪一层坏了"。存储实现使用 {@code store*} 命名，
 *       与审计层同名概念<strong>不得相加</strong>。</li>
 * </ol>
 *
 * <h2>不变量</h2>
 * <pre>{@code
 * requestCount == localHitCount + localNegativeHitCount
 *               + centralHitCount + centralNegativeHitCount + missCount
 *
 * 1 - protectionRate == (1 - localProtectionRate) × (1 - centralProtectionRate)
 * }</pre>
 *
 * <h2>口径约定</h2>
 * <ul>
 *   <li>{@code nullTtl == 0} 时所有 {@code *NegativeHitCount} 恒为 {@code 0}，
 *       {@code protectionRate} 与 {@code valueHitRate} 自动相等；</li>
 *   <li>只有业务 {@code lookup} 计入 {@code requestCount}；对账、热 key 上报、{@code put} /
 *       {@code evict} / {@code clear}、单飞的 double-check 重查<strong>一律不计</strong>；</li>
 *   <li>派生量分母为 0 时返回 {@code -1}（表示"未统计/无样本"），而不是 {@code 0}；</li>
 *   <li>统计关闭时全部计数为 {@code 0}、派生量为 {@code -1}。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public interface CacheStats {

    // ------------------------------------------------------------------ ① 审计层

    /**
     * @return 业务读请求数（入口记一次）
     */
    long requestCount();

    /**
     * @return L1 命中真值次数
     */
    long localHitCount();

    /**
     * @return L1 命中"已确认不存在"次数
     */
    long localNegativeHitCount();

    /**
     * @return L2 命中真值次数
     */
    long centralHitCount();

    /**
     * @return L2 命中"已确认不存在"次数
     */
    long centralNegativeHitCount();

    /**
     * @return 需要回源的次数
     */
    long missCount();

    /**
     * @return loader 实际执行次数（经单飞去重后）
     */
    long sourceLoadCount();

    /**
     * @return loader 返回 {@code null} 的次数（穿透量化）
     */
    long sourceNullCount();

    /**
     * @return loader 抛异常次数
     */
    long loadFailureCount();

    /**
     * @return 写入次数
     */
    long putCount();

    /**
     * @return 失效次数
     */
    long evictCount();

    /**
     * @return 清空次数
     */
    long clearCount();

    // ------------------------------------------------------------------ ② 控制层

    /**
     * @return 热升次数（本地 + 全局）
     */
    long promotionCount();

    /**
     * @return 热降次数（仅中央 {@code COOLING}）
     */
    long demotionCount();

    /**
     * @return 本地阈值触发升热次数
     */
    long localPromotionCount();

    /**
     * @return 中央补偿升热次数
     */
    long globalPromotionCount();

    /**
     * @return 中央判定降热次数
     */
    long globalDemotionCount();

    /**
     * @return {@code DYNAMIC} 下冷 key 的写入被丢弃次数
     */
    long dynamicPutDiscarded();

    /**
     * @return 当前热点集合大小（快照）
     */
    long hotKeyCount();

    /**
     * @return 当前预选桶大小（快照）
     */
    long candidateCount();

    /**
     * @return 热 key 上报与状态查询次数
     */
    long hotSyncCount();

    /**
     * @return 热 key 上报失败次数
     */
    long hotSyncErrorCount();

    /**
     * @return 上报增量被丢弃的次数（缓冲满 / 分区倾斜截断）
     */
    long hotReportDropped();

    /**
     * @return 上报里 {@code delta == 0} 的纯状态查询条数
     */
    long hotCompensationQueryCount();

    /**
     * @return 版本对账轮次
     */
    long versionSyncCount();

    /**
     * @return 对账失败次数
     */
    long versionSyncErrorCount();

    /**
     * @return 对账清除的本地副本数
     */
    long versionInvalidationCount();

    /**
     * @return 对账顺手清掉的过期版本 field 数
     */
    long deadFieldDropped();

    /**
     * @return 回填时发现已有更新版本而丢弃的次数
     */
    long backfillDiscardedCount();

    // ------------------------------------------------------------------ ③ 诊断层

    /**
     * @return 存储层命中次数（与审计层 {@link #localHitCount()} 无关，不得相加）
     */
    long storeHitCount();

    /**
     * @return 存储层未命中次数（同上）
     */
    long storeMissCount();

    /**
     * @return 存储层命令/操作次数
     */
    long commandCount();

    /**
     * @return 中央缓存错误次数
     */
    long centralErrorCount();

    /**
     * @return 编解码错误次数
     */
    long codecErrorCount();

    /**
     * @return 本地条目数；中央实现返回 {@code -1}
     */
    long estimatedSize();

    // ------------------------------------------------------------------ ④ 派生量

    /**
     * 端到端下游保护率（含空值缓存）：{@code 1 - miss/request}。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double protectionRate() {
        long r = requestCount();
        return r == 0 ? -1D : 1D - (double) missCount() / r;
    }

    /**
     * L1 对 L2 的保护率：{@code (localHit + localNeg) / request}。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double localProtectionRate() {
        long r = requestCount();
        return r == 0 ? -1D : (double) (localHitCount() + localNegativeHitCount()) / r;
    }

    /**
     * L2 对数据源的条件保护率：{@code (centralHit + centralNeg) / (request - L1 保护量)}。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double centralProtectionRate() {
        long touched = requestCount() - localHitCount() - localNegativeHitCount();
        return touched <= 0 ? -1D
                : (double) (centralHitCount() + centralNegativeHitCount()) / touched;
    }

    /**
     * 值命中率（只算真值，跨层）：{@code (localHit + centralHit) / request}。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double valueHitRate() {
        long r = requestCount();
        return r == 0 ? -1D : (double) (localHitCount() + centralHitCount()) / r;
    }

    /**
     * 确定性结论里空值占多少：{@code neg / (hit + neg)}。用于解释两个比率之间的差额。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double negativeRatio() {
        long hit = localHitCount() + centralHitCount();
        long neg = localNegativeHitCount() + centralNegativeHitCount();
        return hit + neg == 0 ? -1D : (double) neg / (hit + neg);
    }

    /**
     * 回源里几成是白跑：{@code sourceNullCount / sourceLoadCount}。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double nullLoadRatio() {
        long load = sourceLoadCount();
        return load == 0 ? -1D : (double) sourceNullCount() / load;
    }

    /**
     * 值命中率别名，保留旧名以免语义误解。
     *
     * @return 比率；无样本返回 {@code -1}
     */
    default double hitRate() {
        return valueHitRate();
    }

    /**
     * @return 不可变快照，便于打点、日志与测试断言
     */
    default CacheStatsSnapshot snapshot() {
        return new CacheStatsSnapshot(
                requestCount(), localHitCount(), localNegativeHitCount(),
                centralHitCount(), centralNegativeHitCount(), missCount(),
                sourceLoadCount(), sourceNullCount(), loadFailureCount(),
                putCount(), evictCount(), clearCount(),
                promotionCount(), demotionCount(), localPromotionCount(),
                globalPromotionCount(), globalDemotionCount(), dynamicPutDiscarded(),
                hotKeyCount(), candidateCount(), hotSyncCount(),
                hotSyncErrorCount(), hotReportDropped(), hotCompensationQueryCount(),
                versionSyncCount(), versionSyncErrorCount(), versionInvalidationCount(),
                deadFieldDropped(), backfillDiscardedCount(),
                storeHitCount(), storeMissCount(), commandCount(),
                centralErrorCount(), codecErrorCount(), estimatedSize());
    }

    /**
     * 未启用统计时的空实现：全部计数为 {@code 0}，{@code estimatedSize} 与派生量返回 {@code -1}。
     *
     * @return 空统计
     */
    static CacheStats noop() {
        return NoopStats.INSTANCE;
    }
}
