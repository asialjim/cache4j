package com.asialjim.cache4j.core;

import com.asialjim.cache4j.core.local.LocalStrategy;

import java.time.Duration;

/**
 * <strong>一个 namespace 的全部缓存策略</strong>——运行期唯一使用的配置类型。
 *
 * <p>它是<strong>不可变值类型</strong>：字段全部是强类型（{@code Duration} / 枚举 / 基本类型），
 * 没有 {@code Supplier}、没有 {@code String→枚举} 的逐次解析、没有可空包装类型的拆箱。</p>
 *
 * <p><strong>为什么不用 {@code Supplier} 做"活配置"</strong>：那会让每次缓存操作都要
 * 把几十个 Supplier 求值一遍才能拿到一份一致快照，且 {@code equals} / 日志 / 序列化全失效。
 * 热更新用"替换整个引用"实现——一次 volatile 读即可拿到一致快照，零分配：</p>
 *
 * <pre>
 * AtomicReference&lt;CacheStrategy&gt; strategyRef;
 * CacheStrategy s = strategyRef.get();     // 一次读，天然一致
 * </pre>
 *
 * <p>字段全部<strong>扁平</strong>：版本对账、热 key、全局热 key、中央这几组语义靠注释分段，
 * 不再用 {@code VersionSpec} / {@code DynamicSpec} / {@code GlobalSpec} 嵌套——
 * 嵌套只是把同一个配置拆到四个 record 里，读的时候要跨文件拼。</p>
 *
 * @param namespace             命名空间
 * @param description           用途说明（运维据此判断这个缓存干什么用）
 * @param localStrategy         本地策略
 * @param localMaxSize          本地条目上限；{@code NONE} 时为 0
 * @param localTtl              本地条目存活；{@code NONE} 时为 {@code null}
 * @param localNullTtl          负副本存活 = {@code min(localTtl, centralNullTtl / divisor)}；
 *                              {@code ZERO} 表示不缓存负副本
 * @param versionCheckInterval  对账检查间隔
 * @param versionJitter         检查间隔抖动
 * @param versionRetryBackoff   对账失败退避
 * @param reconcileBatchSize    单批取版本的 key 数
 * @param reconcilePartitions   对账分区数（2 的幂）
 * @param hotThresholdQps       升热阈值（QPS）
 * @param coolQps               降热判定用的 QPS（GLOBAL 模式下本地只升不降）
 * @param hotWindow             频次统计窗口
 * @param hotWindowSlices       窗口切片数
 * @param maxHotKeys            热点集合上限
 * @param hotSyncInterval       热度上报间隔
 * @param hotCooldown           降热后的冷却时长（抑制"降了立刻又升"的抖动）
 * @param coordination          热 key 协调方式
 * @param sketchWidthBits       Count-Min Sketch 宽度（2 的幂）
 * @param sketchDepth           Count-Min Sketch 深度
 * @param hotReportPartitions   上报分区数
 * @param hotReportBatchMax     单次上报的 key 上限
 * @param candidateCapacity     候选桶容量（per-key 窗口表的上限）
 * @param globalWindow          全局热度窗口；{@code null} 表示不启用全局热 key
 * @param globalHotQps          全局升热阈值
 * @param globalCoolQps         全局降热阈值
 * @param globalDemoteWindows   连续多少个窗口低于阈值才降热
 * @param globalStaleWindows    多少窗口无上报视为失效
 * @param globalClientEstimate  单客户端估算份额（用于把全局阈值折算成本地预选阈值）
 * @param centralTtl            中央条目存活
 * @param centralNullTtl        中央负副本存活；{@code ZERO} 表示不缓存
 * @param centralTtlJitter      中央 TTL 抖动比例（0–0.5）
 * @param failOpen              中央不可用时是否放行
 * @param allowNoExpiry         是否允许"永不过期"（{@code centralTtl} 为 0 时）
 * @author Asial Jim
 */
public record CacheStrategy(
        String namespace,
        String description,
        LocalStrategy localStrategy,

        // --- 本地容量与 TTL
        long localMaxSize,
        Duration localTtl,
        Duration localNullTtl,

        // --- 版本对账
        Duration versionCheckInterval,
        Duration versionJitter,
        Duration versionRetryBackoff,
        int reconcileBatchSize,
        int reconcilePartitions,

        // --- 热 key（本地）
        long hotThresholdQps,
        long coolQps,
        Duration hotWindow,
        int hotWindowSlices,
        int maxHotKeys,
        Duration hotSyncInterval,
        Duration hotCooldown,
        Coordination coordination,
        int sketchWidthBits,
        int sketchDepth,
        int hotReportPartitions,
        int hotReportBatchMax,
        int candidateCapacity,

        // --- 热 key（全局）
        Duration globalWindow,
        long globalHotQps,
        long globalCoolQps,
        int globalDemoteWindows,
        int globalStaleWindows,
        int globalClientEstimate,

        // --- 中央
        Duration centralTtl,
        Duration centralNullTtl,
        double centralTtlJitter,
        boolean failOpen,
        boolean allowNoExpiry
) {

    public CacheStrategy {
        if (namespace == null || namespace.isBlank()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "namespace 不能为空");
        }
        if (localStrategy == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "localStrategy 不能为空");
        }
        if (centralTtl == null || centralTtl.isNegative()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralTtl 不能为负：" + centralTtl);
        }
        if (centralNullTtl == null) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralNullTtl 不能为空");
        }
        if (centralTtlJitter < 0 || centralTtlJitter > 0.5) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "centralTtlJitter 必须在 [0, 0.5]：" + centralTtlJitter);
        }
    }

    /**
     * 合并"本地规格 + 中央规格"成<strong>一份</strong>策略。
     *
     * <p>装配时必须先造这一份，再同时交给本地层与中央层——三层共享同一实例，
     * 否则会各自 {@code toStrategy()} 出两份（本地字段真实的、中央字段默认的），
     * 门面取到哪份就决定了行为，这是最难查的一类错。</p>
     *
     * @param local   本地规格
     * @param central 中央规格
     * @return 完整策略
     */
    public static CacheStrategy of(com.asialjim.cache4j.core.local.LocalCacheSpec local,
                                   com.asialjim.cache4j.core.central.CentralCacheSpec central) {
        CacheStrategy base = local.toStrategy();
        return new CacheStrategy(
                base.namespace(), base.description(), base.localStrategy(),
                base.localMaxSize(), base.localTtl(), base.localNullTtl(),
                base.versionCheckInterval(), base.versionJitter(), base.versionRetryBackoff(),
                base.reconcileBatchSize(), base.reconcilePartitions(),
                base.hotThresholdQps(), base.coolQps(), base.hotWindow(), base.hotWindowSlices(),
                base.maxHotKeys(), base.hotSyncInterval(), base.hotCooldown(), base.coordination(),
                base.sketchWidthBits(), base.sketchDepth(), base.hotReportPartitions(),
                base.hotReportBatchMax(), base.candidateCapacity(),
                base.globalWindow(), base.globalHotQps(), base.globalCoolQps(),
                base.globalDemoteWindows(), base.globalStaleWindows(), base.globalClientEstimate(),
                central.ttl(), central.nullTtl(), central.ttlJitter(),
                central.failOpen(), central.allowNoExpiry());
    }

    /**
     * @return 是否有本地缓存（{@code STATIC} / {@code DYNAMIC}）
     */
    public boolean hasLocal() {
        return localStrategy != LocalStrategy.NONE;
    }

    /**
     * @return 是否为动态本地策略
     */
    public boolean isDynamic() {
        return localStrategy == LocalStrategy.DYNAMIC;
    }

    /**
     * @return 中央是否缓存负副本
     */
    public boolean nullCachingEnabled() {
        return centralNullTtl != null && !centralNullTtl.isZero();
    }

    // ------------------------------------------------------------------ 派生量

    /**
     * @return Count-Min Sketch 的宽度（{@code sketchWidthBits} 的 2 的幂）
     */
    public int sketchWidth() {
        return 1 << sketchWidthBits;
    }

    /**
     * @return 时间片时长
     */
    public Duration windowSliceLength() {
        return hotWindow.dividedBy(hotWindowSlices);
    }

    /**
     * @return 升热判定用的<strong>窗口内累计次数</strong>阈值（由 QPS 换算）
     */
    public long hotThresholdCount() {
        return hotThresholdQps * hotWindow.getSeconds();
    }

    /**
     * @return 预选（candidate）判定用的窗口内累计次数阈值
     */
    public long candidateCount() {
        return candidateQps() * hotWindow.getSeconds();
    }

    /**
     * 预选阈值（QPS）：{@code clamp(ceil(globalHotQps / globalClientEstimate), 1, hotThresholdQps - 1)}。
     *
     * <p>必须严格小于 {@code hotThresholdQps}：否则任何到达它的 key 早已本地升热，候选桶恒空。
     * 候选阶段"宁多勿少"——CMS 只会高估，高估在候选阶段是安全方向。</p>
     *
     * @return 预选阈值（QPS）
     */
    public long candidateQps() {
        long upper = hotThresholdQps - 1L;
        if (upper <= 0L) {
            return 1L;
        }
        if (coordination != Coordination.GLOBAL || globalWindow == null) {
            return Math.max(1L, upper);
        }
        long estimate = Math.max(1, globalClientEstimate);
        long share = (globalHotQps + estimate - 1L) / estimate;
        return Math.max(1L, Math.min(share, upper));
    }
}
