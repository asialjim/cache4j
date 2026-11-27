package com.asialjim.cache4j.strategy;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 缓存快翻译器
 * 将缓存快翻译为缓存策略，该缓存策略作为声明缓存策略，注册到缓存策略仓库中
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CacheBlockTranslator {
    public static final CacheBlockTranslator INSTANCE = new CacheBlockTranslator();

    /**
     * 将缓存块翻译为缓存策略
     */
    public CacheStrategyProperty translate(CacheBlock cacheBlock) {
        CacheStrategyProperty object = new CacheStrategyProperty();
        // 本翻译器产出的所有时长字段一律是「秒」——CacheStrategy 与 CacheStrategyProperty
        // 都不再携带时间单位，运维侧的单位选择（5 分钟 / 2 小时）留待
        // CacheStrategyAdministrator 换算成秒后落到这里。
        object.setNamespace(cacheBlock.getNamespace());
        object.setDescription(cacheBlock.getDescription());
        object.setVersion(0);
        object.setLocalStrategy(cacheBlock.getLocalStrategy().name());
        // ================= 本地容量 =================
        /*
         * 本地容量 10000 条。
         * 业务推算：一个微服务实例堆 2~4GB，缓存的 DTO 平均 0.5~1KB（含嵌套对象与字符串），
         * 10000 条约占 10MB，是堆的 0.5% 左右 —— 这是"本地缓存值得存在"的最小量级。
         * 1000 条只有 1MB，还没热起来就被 LRU 挤空，DYNAMIC 策略会退化成几乎不缓存。
         * 上限也不能再放大：本地缓存的价值在"快"，不在于"全"；全量查询该走中央。
         */
        object.setLocalMaxSize(10000L);

        /*
         * 本地副本 120 秒。
         * 本地 TTL 的作用不是"新鲜度"（那是版本对账的事），而是兜底 ——
         * 万一中央不可用、对账一直失败，副本最多还能活这么久。
         * 对账间隔是 30 秒，120 秒给了 4 次核对机会；再长就成了"对账失效也没人发现"。
         * 再短（例如 30 秒）会让正常路径下的命中率显著下降，因为副本活不过两次对账。
         */
        object.setLocalTtl(120L);

        /*
         * 空值副本 = localTtl / 4 = 30 秒。
         * 负副本用于防穿透，但比正副本更危险：一个"不存在"的结论很容易变成"刚存在"，
         * 例如用户刚注册、商品刚上架。30 秒是业务可接受的最长"说了不在其实在"的窗口；
         * 同时 30 秒也足够挡住穿透攻击的主要部分（攻击通常持续数十秒到几分钟）。
         * 除数取 10 会让空值 TTL 只有 12 秒，防穿透效果接近于零。
         */
        object.setLocalNullTtlDivisor(4);

        // ================= 版本对账 =================
        /*
         * 对账间隔 30 秒 = 跨节点数据不一致的最大延迟。
         * 这个值等价于"业务承诺的最终一致窗口"，能接受 30 秒陈旧的场景才该用缓存。
         * 与 hotSyncInterval 对齐，两个离散步长一致，运维只需记一个数。
         * 更短：每个 namespace 每秒都在打中央，namespace 一多就是可观的固定开销。
         * 更长：本地副本可能在过期前都等不到一次核对。
         */
        object.setVersionCheckInterval(30L);
        /*
         * 抖动 5 秒：实际间隔落在 30~35 秒。
         * 所有节点读同一份配置、常常同时启动，固定间隔会让它们在同一秒一起打中央。
         * 抖动只往长了偏 —— 配 30 秒却 25 秒触发会违背运维的心智模型。
         */
        object.setVersionJitter(5L);
        /*
         * 失败退避 10 秒：对账抛异常后下一次推迟 10 秒，而不是等满 30 秒。
         * 要明显小于正常间隔，否则一次瞬时抖动就让该 namespace 整整一个周期无人核对；
         * 也不能太小，否则中央故障时每次读都重试，把一次故障放大成流量风暴。
         */
        object.setVersionRetryBackoff(10L);
        /*
         * 单次对账 512 个 key。
         * 与分区数成对推导：本地上限 10000、分 32 个区 → 每区约 312 个，
         * 落在 512 以内，一次 HMGET 就能覆盖整个分区，不需要二次细分。
         * 512 个 key 的 HMGET 在 Redis 上是 1~2ms 量级，仍远小于一次业务查询。
         */
        object.setReconcileBatchSize(512);
        /*
         * 对账分区 32 个。
         * 分区不是定时轮转，而是"访问到哪个 key，就顺手核对它所在分区"，
         * 所以分区数要满足 reconcilePartitions >= localMaxSize / reconcileBatchSize（10000/512 ≈ 20），
         * 取 32 留出余量：分区越多，单次对账的批量越小、对读路径的延迟影响越轻；
         * 但过多会让每个区里的 key 太少，冷 key 的分区长期轮不到。
         */
        object.setReconcilePartitions(32);

        // ================= 热 key 判定 =================
        /*
         * 升热阈值 50 QPS（单节点、单 key）。
         * 业务推算：一个中等规模业务，单 namespace 总 QPS 约 1000，长尾 key 占绝大多数；
         * 50 QPS 意味着这个 key 吃掉了 5% 的总流量 —— 已经是明确的头部。
         * 定得比这更高（如 100）会让中长尾的"温 key"也留在中央，本地缓存只覆盖最顶尖的几个，
         * 命中率上不去；而本地缓存本身很便宜（内存），中央缓存才贵（网络 + Redis），
         * 所以判据应该往"多缓存"的方向偏。
         * 定得更低（如 10）会让大量普通 key 挤进热集合，把真正的热点挤出 maxHotKeys。
         */
        object.setHotThresholdQps(50L);
        // 降热阈值 = 升热阈值的 30%，形成滞回区，避免在阈值附近反复升降
        object.setCoolQps(15L);
        /*
         * 统计窗口 30 秒。
         * 这个值同时决定两件事的灵敏度：突发热点多久能升热、热度消退多久能降热。
         * 30 秒意味着一个真热点在爆发后约半分钟进入本地缓存 —— 对绝大多数业务够快；
         * 再短（10 秒）会被瞬时尖峰带偏，把一次性批量任务当成热点。
         * 与对账间隔（30 秒）取值相同，两者都是"30 秒粒度"的离散步长。
         */
        object.setHotWindow(30L);
        // 窗口切片 30 片 = 1 秒一片。HotWindow 按 windowMillis / slices 算片长，
        // 取 30 才能整除（1000ms/片）；否则片长会出现 937ms 这种非整数，语义就模糊了。
        object.setHotWindowSlices(30);
        /*
         * 热 key 上限 200 = 本地容量的 2%。
         * DYNAMIC 的意义是"少数热点占用本地"，这个值必须远小于 localMaxSize。
         * 200 个热 key × 1KB ≈ 200KB，对本地内存毫无压力；
         * 而真正值得单独保护、且在 30 秒窗口里稳定超过 50 QPS 的 key，通常在几十个量级，
         * 200 给了 3~4 倍余量，避免热点轮换时互相挤出。
         */
        object.setMaxHotKeys(200);
        /*
         * 上报间隔 20 秒 = 窗口的 2/3。
         * 必须小于统计窗口，否则窗口里的数据还没上报就过期了，中央永远慢一拍。
         * 取 2/3 而不是 1/2，是因为要保证任何时间点上报时，窗口里至少还有 1/3 的有效数据。
         */
        object.setHotSyncInterval(20L);
        /*
         * 冷却期 60 秒 = 2 个统计窗口。
         * 降热后压一段时间不许再升，避免"降→升→降"的抖动。
         * 短于一个窗口时，冷却结束那一刻窗口里还留着上次升热的计数，读一下立刻就又升热；
         * 两个窗口能保证冷却结束时窗口已经完全换了一批数据。
         */
        object.setHotCooldown(60L);
        // 多节点协作：降热以中央裁决为准（本地只看到局部流量，判断不了全局热度）
        object.setCoordination("GLOBAL");

        // ================= 频次草图（CMS）=================
        /*
         * 草图宽度 14 位 = 16384 槽。
         * 宽度决定"多少 key 会撞进同一格"，即高估的概率：CMS 只会高估，幅度约 e / width，
         * 2^14 时约 0.017%（一万个不同 key 里约两个会读到别人的计数）。
         * 更宽：内存按 width × depth × 8 字节线性涨，且每个 namespace 一份；
         * 更窄：大量无关 key 撞在一起，每个都显得"读过很多次"，误升热挤掉真热点。
         */
        object.setSketchWidthBits(14);
        /*
         * 草图深度 4 层：CMS 取各层估计的最小值，层数越多，高估要同时骗过所有层。
         * 单层 CMS 的碰撞是单向累积的 —— 格子一旦被别的 key 抬高，里面的所有 key 永久偏高。
         * 4 层是 CMS 的常见取值，在此之上的收益已进入长尾，而每层都多一次内存访问与比较。
         */
        object.setSketchDepth(4);

        // ================= 上报与候选 =================
        /*
         * 上报拆分 8 片：一批增量拆成 8 次 report 调用，同一轮内全部完成。
         * 拆分的目的是控制单次请求体积，不是省流量：几个大 key 的 report 在跨网络调用时
         * 会变成尾延迟的来源，拆开后每片小、可独立失败。
         * 8 片对应 maxHotKeys 200 → 每片约 25 个 key，是"批次够小"与"调用次数够少"的平衡点；
         * 拆成 32 片会让每片只剩 6 个 key，纯粹是在付 32 次网络往返的代价。
         */
        object.setHotReportPartitions(8);
        // 单次上报批量 256 > maxHotKeys 200：一轮就能把全部热 key 报出去，
        // 不需要多轮补齐（多轮会把上报周期人为放大数倍）
        object.setHotReportBatchMax(256);
        /*
         * 精确窗口表 512 = maxHotKeys 的 2.5 倍。
         * 这张表装的是"已达候选阈值、正在精确计数"的 key，是升热的必经阶段。
         * 容量小于 maxHotKeys 时，接近阈值的 key 会互相挤出，真正的热点还没数到阈值
         * 就被 LRU 淘汰，永远升不了热。2.5 倍是"候选池要显著宽于最终热集"的经验倍率。
         */
        object.setCandidateCapacity(512);

        // ================= 全局视角 =================
        // 全局窗口与本地窗口一致：两层看同一段时间的热度，避免出现"本地热、全局冷"的错判
        object.setGlobalWindow(object.getHotWindow());
        /*
         * 全局升热阈值 = 单节点阈值。
         * 候选阈值按 U = ceil(globalHotQps / globalClientEstimate) 摊到单节点：
         * 50 / 8 ≈ 7 QPS，明显小于升热阈值 50 —— 这正是候选阶段该有的位置（先占坑、后确认）。
         * 若把它设成"单节点阈值 × 节点数"，摊派后恰好等于升热阈值，候选阶段就失去意义。
         */
        object.setGlobalHotQps(object.getHotThresholdQps());
        object.setGlobalCoolQps(object.getCoolQps());
        // 连续 2 个窗口判降温才降热：中央偶尔抖一次不该让真热点丢掉本地副本
        object.setGlobalDemoteWindows(2);
        /*
         * 连续 3 个上报周期没收到明确表态 → 视为陈旧。
         * 20 秒 × 3 = 60 秒，与冷却期同量级：中央一分钟都说不出这个 key 的状态，
         * 说明双方对"热点集合"的认知已经不一致（换实例、策略重置、中央丢数据），
         * 继续当热点只会白占本地内存。
         */
        object.setGlobalStaleWindows(3);
        /*
         * 参与分摊的客户端数 8。
         * 微服务典型部署规模是 3~10 个实例，8 是其中的中位数。
         * 这个值直接影响候选阈值的摊派结果，部署形态差异大时应该由业务方覆盖。
         */
        object.setGlobalClientEstimate(8);

        // ================= 中央缓存 =================
        /*
         * 中央副本 7200 秒 = 2 小时。
         * 中央 TTL 的真正作用是"清理不再被访问的 key"，而不是"保证新鲜度"——
         * 新鲜度由版本对账负责。所以它只该跟"数据还会不会被读"有关：
         * 2 小时能覆盖绝大多数业务的活跃周期（一次会话、一个工作时段），
         * 又不会让 Redis 里堆满一周没人碰的数据。
         * 定得太长（如 5 小时）会在 namespace 多、key 基数大时显著抬高 Redis 内存。
         */
        object.setCentralTtl(7_200L);
        /*
         * 中央空值副本 300 秒 = 5 分钟。
         * 这里必须比上面短得多：空值代表"这个 key 不存在"，而它随时可能变成存在
         * （新用户注册、商品上架、订单创建）。空值在中央待 1 小时是灾难性的 ——
         * 用户注册后一小时查自己都查不到。
         * 5 分钟足够挡住穿透攻击的持续段，又不至于让新数据长期不可见。
         * 注意：这个值 > 0 同时决定了本地是否允许负副本（nullAble 由此派生），
         * 所以它不能配成 0，否则整条防穿透链路关闭。
         */
        object.setCentralNullTtl(300L);
        /*
         * TTL 抖动 10%：实际 TTL 落在 6480~7920 秒。
         * 防的是"缓存雪崩"——大批 key 在同一时刻写入（例如启动预热、批量刷新），
         * 没有抖动它们就会在同一时刻集体过期，把流量瞬间全砸给数据源。
         * 10% 能把过期时刻摊到 24 分钟的区间里，足够打散任何批量写入的同步性。
         * 1% 只能摊开 72 秒，对"整点批量刷新"这类场景仍然会形成尖峰。
         */
        object.setCentralTtlJitter(0.10);
        // 中央故障时降级为"只有本地缓存"，而不是整体不可用 —— 缓存挂了不该让业务跟着挂
        object.setFailOpen(true);
        // 明确不缓存"永不过期"的条目：没有 TTL 兜底，一旦版本机制失效就是永久脏数据
        object.setAllowNoExpiry(false);
        return object;
    }
}
