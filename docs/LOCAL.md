# 本地缓存设计（`cache4j-local` / `cache4j-local-caffeine`）

> 本文是 [总体设计](DESIGN.md) 的展开，只讨论本地缓存（L1）。中央缓存见 [CENTRAL.md](CENTRAL.md)。

---

## 1. 本地缓存 SPI（通用契约在 `cache4j-core`，本地侧在 `cache4j-local`）

`Cache`、`Lookup`、`CacheKey` 是 core 的通用契约；`LocalCache`、`LocalCacheSpec`、`LocalStrategy` 是本地侧 SPI（接口由 core 暴露），由 `cache4j-local` 负责实现与装配。

```java
public interface LocalCache<V> extends Cache<CacheKey, LocalEntry<V>> {

    LocalStrategy strategy();

    /** NONE 返回 false；STATIC/DYNAMIC 返回 true */
    boolean available();

    /**
     * 便捷读：命中返回条目（含 revision），未命中返回 null。
     */
    default LocalEntry<V> entry(CacheKey key) {
        Lookup<LocalEntry<V>> l = lookup(key);
        return l instanceof Lookup.Hit<LocalEntry<V>> h ? h.value() : null;
    }

    /**
     * 当前持有的全部 key（≤ maxSize）。对账需要它：一次 HMGET 只取本地关心的 field。
     * 返回快照即可，不要求强一致。
     */
    Set<CacheKey> keys();

    /**
     * 记录一次访问（按逻辑 key，与版本无关）。
     * STATIC 返回 true；DYNAMIC 返回“本次访问后是否处于热点状态”；NONE 返回 false。
     * 不负责读取值。
     */
    boolean access(CacheKey key);

    /**
     * DYNAMIC 的访问驱动热同步。
     * STATIC / NONE 为空实现；DYNAMIC 在到点后的访问中上报增量并查询中央状态。
     * 不负责读取值。
     */
    default void hotSync(CacheKey key) {}

    /**
     * 本地实现的自我维护：TTL 清理 / 容量淘汰 / 冷却集合清理。
     * 由本地实现自行决定触发方式（访问路径惰性触发或内部清理线程）；
     * 核心编排不创建调度线程，也不保证调用它。
     * DYNAMIC 的频次窗口旋转在 access 路径上惰性完成。
     */
    void maintain();

    LocalCacheSpec spec();

    @Override
    default String name() {
        return spec().namespace();
    }
}
```

关键约定：

- 本地 key 就是 `CacheKey`；value 是 `LocalEntry(value, revision, kind)`（见 DESIGN §4.3.1）。失效按 key 覆盖/删除，**不存在 generation**。
- `access` 接收逻辑 `CacheKey`：频率统计与热点身份只与 key 有关，与版本无关。
- `lookup` **不记录频率**，频率记录只由 `access` 负责。这样两层编排可以明确控制"一次业务读只记一次"。
- `access` 必须在 `lookup` 之前调用；`DYNAMIC` 依赖这个顺序完成"先升热、再回填"。
- `put` 是否真正落盘由实现决定：`NONE` 拒绝，`DYNAMIC` 只接受热点 key，`STATIC` 全部接受。
- 所有实现线程安全；`close` 幂等。

```java
public enum LocalStrategy {
    NONE, STATIC, DYNAMIC
}
```

---

## 2. 配置

```java
public record LocalCacheSpec(
        String namespace,
        LocalStrategy strategy,
        long maxSize,
        Duration ttl,
        VersionSpec version,
        DynamicSpec dynamic
) {

    public record VersionSpec(
            Duration checkInterval,    // 命名空间级对账间隔；必须为正
            double jitter,             // 对账间隔抖动比例
            Duration retryBackoff,     // 对账失败退避
            int reconcileBatchSize     // 一次 HMGET 的批量大小
    ) {}

    public record DynamicSpec(
            int localHotThreshold,        // 本地窗口内达到多少次算热点
            Duration window,              // 本地频率统计窗口
            int maxHotKeys,               // 本地热点集合上限
            Duration hotTtl,              // 热点 key 在本地的最长存活时间
            Duration syncInterval,        // 热 key 同步点间隔（访问驱动）
            Duration cooldown,            // 降热后多久允许再次升热
            Coordination coordination,    // local / global
            GlobalSpec global,            // coordination=global 时必填
            int sketchWidth,              // 频率草图宽度，2 的幂
            int sketchDepth               // 频率草图深度
    ) {}

    public record GlobalSpec(
            Duration window,              // 中央窗口
            long hotThreshold,            // 集群级升热阈值
            long coolThreshold,           // 集群级降热阈值
            int demoteWindows,            // 连续低于冷阈值的窗口数
            int staleWindows,             // 无上报多少个窗口后判 COOLING
            int clientEstimate            // 连接同一套 Redis 的客户端实例数（推导预选阈值）
    ) {}

    public enum Coordination { LOCAL, GLOBAL }
}
```

默认值（待压测校准）：

| 参数 | `STATIC` 默认 | `DYNAMIC` 默认 | 说明 |
|---|---:|---:|---|
| `maxSize` | 10_000 | 10_000 | 本地条目上限；必须 > 0 |
| `ttl` | 30s | 30s | 本地副本最长存活；必须 > 0 |
| `version.checkInterval` | 1s | 1s | 命名空间级对账间隔；决定该命名空间的最大不一致窗口 |
| `version.jitter` | 0.1 | 0.1 | 对账间隔抖动，避免多节点同时打中央 |
| `version.retryBackoff` | 200ms | 200ms | 对账失败后的退避 |
| `version.reconcileBatchSize` | 1000 | 1000 | 一次 `HMGET` 的批量大小 |
| `localHotThreshold` | — | 5 | 双窗口合计访问次数阈值，1–255 |
| `window` | — | 10s | 本地频率窗口 |
| `maxHotKeys` | — | 1_000 | 热点集合上限，必须 ≤ `maxSize` |
| `hotTtl` | — | 60s | 热点 key 最长本地存活；必须 ≤ 中央 TTL |
| `syncInterval` | — | 1s | 热 key 同步点间隔；无访问不触发 |
| `candidateCapacity` | — | = `maxHotKeys` | 预选桶总容量；每分区 `candidateCapacity / hotReportPartitions` |
| `hotReportPartitions` | — | 16 | 上报分区数；2 的幂、≥ 2 |
| `hotReportBatchMax` | — | 64 | 单次上报 key 数硬上限（防倾斜分区） |
| `cooldown` | — | 30s | 降热冷却期，防止抖动 |
| `coordination` | — | `LOCAL`（单机）/ `GLOBAL`（生产） | 是否启用跨节点热度协调 |
| `global.window` | — | 10s | 中央热 key 窗口 |
| `global.hotThreshold` | — | 按业务压测 | 集群级升热阈值 |
| `global.coolThreshold` | — | `global.hotThreshold / 4` | 集群级降热阈值，必须小于升热阈值 |
| `global.demoteWindows` | — | 3 | 连续低于冷阈值后判 `COOLING` |
| `global.staleWindows` | — | 3 | 无上报多少个窗口后判 `COOLING` |
| `global.clientEstimate` | — | 1 | 连接同一套 Redis 的客户端实例数；决定预选阈值 `U` |
| `sketchWidth` | — | 4_096 | 2 的幂，≥ 1024 |
| `sketchDepth` | — | 4 | ≥ 2 |
| `expireAfterAccess` | false | false | v1 只支持写后过期；访问续期列为后续 |

> 本地 TTL 必须为正，这是硬约束：本地是副本，必须有兜底过期。但要注意：**TTL 不再是变更感知手段**，变更感知由 `version.checkInterval` 决定；TTL 只负责资源回收与版本校验长期失败时的兜底。

### 2.1 构建方式

`LocalCacheSpec` 提供静态工厂，避免调用方拼错组合（例如 `DYNAMIC` 忘了配 `dynamic`）：

```java
LocalCacheSpec.noLocal("user:detail");                        // NONE

LocalCacheSpec.staticCache("user:detail", 10_000, ttl);       // STATIC，version 用默认值

LocalCacheSpec.dynamicCache("user:detail", 10_000, ttl,
        DynamicSpec.builder()
                .localHotThreshold(100)
                .window(Duration.ofSeconds(10))
                .maxHotKeys(1_000)
                .hotTtl(Duration.ofSeconds(60))
                .syncInterval(Duration.ofSeconds(1))
                .coordination(Coordination.GLOBAL)
                .global(GlobalSpec.builder()
                        .window(Duration.ofSeconds(10))
                        .hotThreshold(200_000)
                        .coolThreshold(50_000)
                        .demoteWindows(3)
                        .staleWindows(3)
                        .build())
                .build());
```

- 三个工厂都会校验参数；`NONE` 忽略 `maxSize` / `ttl` / `version`，非零配置记 WARN；
- `STATIC` / `DYNAMIC` 的 `version` 不允许为 `null`，缺省由工厂填充默认 `VersionSpec`；
- `DYNAMIC` 且 `coordination=GLOBAL` 时 `global` 必填，`coordination=LOCAL` 时忽略 `global` 并记 INFO；
- 工厂只做**本地侧**校验；"必须有 `HotKeyCoordinator`"的校验在 `TwoLevelCache` 构建阶段完成，因为它需要看到装配上下文。

---

## 3. `NONE`：本地不存值

### 3.1 语义

- `available()` 返回 `false`；
- `lookup` 永远返回 `Miss`；
- `access` 返回 `false`，不记录频率；
- `hotSync` 为空实现；
- `put` / `evict` / `clear` 都是空操作；
- 不参与版本校验：没有本地副本，就没有需要失效的 key；
- 统计全为 0。

### 3.2 两层编排行为

```text
read(key):
  → 中央 lookup
  → 命中：返回（不回填本地）
  → 未命中：loader 回源 → 写中央（不回填本地）
```

### 3.3 适用场景

- 对一致性敏感、不能接受本地副本的数据；
- value 很大，本地缓存容易放大内存；
- 写多读少、命中率低的 key；
- 本地缓存故障时需要完全绕过 L1 的降级场景。

### 3.4 实现

`NoOpLocalCache`：无状态单例，`close` 无资源。

---

## 4. `STATIC`：静态本地缓存 + 版本化

### 4.1 语义

- 读：本地命中直接返回；未命中查中央，中央命中回填本地；中央未命中由 loader 回源，回源后写中央并回填本地。
- 写：中央优先，`put` 返回该 key 的新版本；本地用新版本覆盖副本。
- 失效：先中央（`DEL data` + `HDEL __rev` field），再清本地副本。
- 本地条目有 `maxSize` 与 `ttl`，由存储实现负责淘汰与过期。
- 不记录频率，不做升降热；但**参与版本校验**。

### 4.2 版本化的本地副本

`STATIC` 的本地 key 就是 `CacheKey`，版本与条目种类在 value 位置：

```java
LocalEntry<User> e = LocalEntry.hit(value, revision);       // 真值
LocalEntry<User> n = LocalEntry.negative(revision);        // 已确认不存在（仅 nullTtl > 0）
```

- `revision`：这条副本对应的 key 级版本（中央时间戳）；
- `kind`：`VALUE` / `NEGATIVE`；负副本与中央负缓存同生命周期，TTL = `min(localTtl, nullTtl)`；
- 中央版本变化时，**这条副本被新值覆盖**（中央已删时被清除），不产生新的本地 key；
- 因此不需要 generation、不需要遍历删除，也不会有"旧代条目等待回收"。

### 4.3 对账（reconcile）

```text
access:
    if 未到对账点（命名空间级信号量）:
        → 本地命中 / 未命中回中央
    else:
        → HMGET __rev <本地该命名空间下的全部 key>（按 reconcileBatchSize 分批）
        → 版本不一致 / 中央缺字段 / expireAt 已过 → local.evict(key)
        → expireAt 已过的 field 收集起来批量 HDEL
        → reschedule(now + checkInterval ± jitter)
```

- 信号量属于**命名空间**（`nextSyncAtNanos` + `syncing` CAS），不是每 key 一个；
- **一次对账覆盖全部本地副本**，所以低频 key 不会被饿死；
- 载荷 = 本地已知 key 数（≤ `maxSize`），不是该命名空间在中央的 key 总数；
- 本地实现只负责存 `LocalEntry` 并提供 `keys()`；比对逻辑在 `TwoLevelCache`，本地不感知中央版本的生成规则；
- 对账失败按 `retryBackoff` 推迟下次，不改任何副本；
- 对账是**逐条 evict**，不存在"半失效"窗口，所以不需要 `version.during` 之类的开关。

### 4.4 一致性窗口

```text
变更感知上界 ≈ checkInterval + 一次访问的触发延迟 + 时钟粒度
```

多节点写同一 key 时，其他节点的旧值最多存活一个 `checkInterval`（再加上实际访问到来前的空闲时间；空闲期间没有返回旧值，因此不构成"读到脏数据"）。

如果业务不能接受这个窗口：

- 改用 `NONE`；
- 或把 `checkInterval` 调小（例如 200–500ms），代价是更多版本 I/O；
- 不要试图用"每次读都查中央"来伪装 `STATIC`，那等价于 `NONE` 且更慢；
- 本地 TTL 不再是这个窗口的组成部分，把 TTL 调短不会加快变更感知，只会降低命中率。

### 4.5 淘汰

由 `LocalCacheProvider` 决定：

| 实现 | 淘汰算法 | 适用 |
|---|---|---|
| `CaffeineLocalCache` | Window TinyLFU + 容量上限 + TTL | 生产推荐 |
| `HashMapLocalCache` | 分段 LRU + 惰性过期 + 定时清理 | 零依赖兜底、测试、小服务 |

`maxSize` 必须显式配置；不提供"无上限"的本地缓存。

> 淘汰只影响"本地还有没有这个值"，不影响版本语义；被淘汰的 key 下一次访问回中央读取即可，热点身份（`DYNAMIC`）不受容量淘汰影响。

---

## 5. `DYNAMIC`：热点探测与热升热降

### 5.1 目标

`STATIC` 会把所有访问过的 key 都放进本地，热点不突出、内存占用大。`DYNAMIC` 只让**高频 key** 进入本地：

- 冷 key：始终走中央缓存（或回源），不占本地内存；
- 热 key：升入本地，后续直接命中；
- 热 key 变冷：降出本地，释放内存；
- 热度判定必须同时考虑**单节点频次**与**集群合计频次**；
- 频率统计本身必须有界，不能随 key 基数无限增长；
- 没有访问时，不产生任何中央 I/O。

### 5.2 为什么不做在 Caffeine 里

Caffeine 的 Window TinyLFU 是**缓存内部淘汰策略**，它回答"容量满时淘汰谁"，不回答"这个 key 该不该进入本地"。`DYNAMIC` 需要的是**准入门槛 + 热点集合 + 跨节点热度协调 + 降热**，与具体存储无关。

因此采用装饰器：

```text
DynamicLocalCache (频率草图 + 热点集合 + 访问驱动热同步 + 中央状态)
        ↓
LocalCache (Caffeine / HashMap)
```

这样 Caffeine 与 HashMap 都能复用同一套热键逻辑。

### 5.2.1 四条热度规则

1. **本地升热立即生效**：本地窗口频次达到 `localHotThreshold`，直接升入本地，不等中央。
2. **本地不升热 ≠ 不热**：在访问驱动的热同步事件中向中央做补偿查询；中央判定 `HOT` 则升热。
3. **降热只认中央**：本地频次下降、空闲都不构成降热依据；只有中央返回 `COOLING` 才真正降热。
4. **预选阈值严格低于本地升热阈值**：预选桶只收“本地还不够热、但集群口径可能够热”的 key，门槛 `U = clamp(ceil(global.hotThreshold / global.clientEstimate), 1, localHotThreshold - 1)`。详见 §5.3.2 的预选桶。

例外（不算语义降热）：

- value 的 TTL 到期；
- 热点集合容量满时的 LRU 淘汰；
- 缓存关闭 / 实例下线。

> 典型场景：50 个节点，每个节点同一窗口 1 万次，`localHotThreshold=2 万`，单节点都不升热；但中央窗口合计 50 万次，判定 `HOT`，各节点在下次热同步时补偿升热。

### 5.3 数据结构

#### 5.3.1 滑动窗口频率草图

> **计什么：只计读频次。** 只有读路径的 `access()` 记账；`put` / `evict` / `clear` / 对账 / 热 key 上报一律不计频。
> 因为 hotkey 的目的就是**减少对 L2 的读**；写必须穿透中央（本地存不存副本都不影响 L2 写压力），写在这套机制里没有可优化的对象。

使用**双桶 Count-Min Sketch**：

- 两个 `Sketch`：`current`、`previous`；
- 每次访问 `current.increment(key)`；
- 每 `window` 旋转一次：`previous = current`，`current = new`；
- 频率估计 = `current.estimate(key) + previous.estimate(key)`；
- 每个桶内是 8 位计数器（0–255），宽度 `sketchWidth`，深度 `sketchDepth`；
- 每个 key 取 `depth` 个哈希位置，估计值取最小值，降低碰撞高估。

内存：

```text
2 * sketchWidth * sketchDepth bytes
默认 2 * 4096 * 4 = 32 KB / 每个 DYNAMIC 缓存
```

选择双桶而不是"单桶 + 周期减半"的原因：

- 双桶的"最近两个窗口"语义更容易解释和调参；
- 不需要遍历整个草图做衰减；
- 旋转是 O(1) 的引用交换，旧桶由 GC 回收。

碰撞的影响：

- Count-Min Sketch 只会**高估**，不会低估；
- 高估导致"冷 key 被误升为热点"，后果是少量多余本地内存，不会返回错误数据；
- 热点集合有 `maxHotKeys` 上限，误升的规模有界；
- 本地 TTL 仍然兜底，误升的 key 不会永久驻留。

#### 5.3.2 热点集合

```java
ConcurrentHashMap<CacheKey, HotEntry> hotKeys; // 上限 maxHotKeys
ConcurrentLinkedQueue<CacheKey> lru;           // 近似 LRU，惰性删除

record HotEntry(
        long promotedAtNanos,
        long lastAccessNanos,
        long lastSyncNanos,
        HotKeyStatus lastCentralStatus,
        int  lastEstimate
) {}
```

- `hotKeys` 只保存**已升热**的 key；
- `lru` 用于容量满时挑最久未访问的热点 key；出队时若 key 已不在 `hotKeys` 则跳过（惰性删除）；
- 热点集合的大小必须 ≤ `maxHotKeys`；
- 热点集合与底层存储在容量上解耦：Caffeine 仍可能因自身容量淘汰某个热点 key；此时 `lookup` 未命中，下次访问会重新写入。建议 `maxSize >= 1.25 * maxHotKeys`，减少"热点集合说它在、底层却没有"的抖动；
- 淘汰/降热时直接 `delegate.evict(key)`：per-key 版本下本地 key 就是 `CacheKey`，不需要记住"哪一代"；
- `lastCentralStatus` 是最近一次中央返回的状态，仅用于观测和调试，不作为降热的唯一本地依据。

**预选桶（candidate）**——与热点集合同级，用于把"本地还不够热、但集群口径可能已经够热"的 key 送去问中央：

```java
// 与 hotByPartition 同构，长度 = dynamic.hotReportPartitions
List<Set<CacheKey>> candidateByPartition;
long candidateThreshold;   // U：派生值，不是手配项
```

- 入桶条件：`U <= sketchEstimate(key) < localHotThreshold`（用双窗口合计估计值）；
- 派生口径：

  ```text
  U = clamp( ceil(global.hotThreshold / global.clientEstimate),
             1, localHotThreshold - 1 )
  ```

  两条硬约束：**`U` 必须严格小于 `localHotThreshold`**（否则任何到达 U 的 key 早已本地升热，候选桶恒空）；均匀分布下全局热 key 的单节点份额是 `hotThreshold / clientEstimate`，只有 `U <= 该份额` 才能保证它在每个节点都入桶、被上报、中央看到合计后判 `HOT`；
- 窗口口径：`U` 来自 `global.window`、`localHotThreshold` 来自 `dynamic.window`，两者不等时按比例换算 `U`；
- 容量与淘汰：每分区容量 `B ≈ candidateCapacity / hotReportPartitions`（默认 `candidateCapacity = maxHotKeys`）；满时**按草图估计值保留 top-B**（不用 LRU/FIFO——“预选”的意义是“未来可能热”，频次是唯一有意义的排序依据），插入是 O(B) 的插入排序，B 约 63，可忽略；
- 出桶：已被升热、估计值跌破 `U`、降热、容量淘汰、缓存关闭；
- `coordination=LOCAL` 时预选桶不生效（没有中央可问）。

#### 5.3.3 冷却集合

```java
ConcurrentHashMap<CacheKey, Long> cooldownUntilNanos; // 上限 maxHotKeys
```

- 降热后写入 `now + cooldown`；
- 冷却期内即使频率再次达标，也**不允许**升热；
- **它只挡一种抖动**：本地阈值驱动的重升热（降热后草图估计值仍 ≥ `localHotThreshold`，规则 1 会立刻重新升热）。补偿升热那侧的振荡不归它管，靠中央的滞回带（`coolThreshold < hotThreshold`）与 `demoteWindows` / `staleWindows`；
- 不参与上报，也不参与降热判定（降热只认中央 `COOLING`）；
- **清理不靠 `maintain()`**（核心不保证调用它），而是在 `access()` 路径上做两件事（均为 O(1)）：
  - **读时惰性删**：`isCooling` 发现条目已过期就 `remove(key, until)`（带值比较，避免并发误删新写入的冷却期）。覆盖的正好是"降热后又被访问"的那批——也是唯一会引发抖动的那批；
  - **摊销清扫**：每 1024 次 `access` 取一条过期项删掉，排干"降热后再也没人访问"的僵尸项；
- 容量满时按**最早到期**淘汰：被挤掉的是即将过期的项，损失接近零，且新降热的 key 总能挤掉一条已过期的，**新冷却不会丢**；
- 冷却只影响"再次升热"，不影响"保持当前状态"。

#### 5.3.4 待上报增量

```java
ConcurrentHashMap<CacheKey, LongAdder> pendingDelta;   // 有界
ConcurrentLinkedQueue<CacheKey> pendingOrder;          // 上报顺序 + 淘汰顺序
```

- 每次访问只做本地 `increment`，**不直接写中央**；
- 只跟踪**已升热 key** 与**本地候选 top-K**（`pendingOrder` 的有界队列）；其他冷 key 不分配计数器，避免随 key 基数无限增长；
- 缓冲有界，满时丢弃最低频的尾部，并记 `hotReportDropped`；
- 上报成功后从缓冲中扣除已上报的增量（`take` 语义），失败则**整批回填**；
- `hotSync` 一次上报的是**当前访问的 key 所属分区**里的全部已热 key + 预选 key（见 §5.5），不是单个 key：带增量的用 `take`，其余用 `0` 做纯状态查询；
- **降热时必须清掉该 key 的 `pendingDelta` 与分区桶里的条目**，否则会给一个已经降热的 key 继续上报；
- 分区桶：`List<Set<CacheKey>> hotByPartition` / `candidateByPartition`，长度 = `dynamic.hotReportPartitions`，在升热/降热/淘汰时同步增删，避免每次上报扫描整个 `hotKeys`；分区号 = `stableHash(encodedKey) & (N-1)`。

#### 5.3.5 访问驱动的同步门控

```java
final class HotSyncGate {

    private final AtomicBoolean syncing = new AtomicBoolean();
    private volatile long nextSyncAtNanos;   // 0 表示尚未初始化

    boolean due(long now) {
        long next = nextSyncAtNanos;
        return next != 0 && now - next >= 0;
    }

    /** 第一次访问时调用：只设置检查点，不触发同步 */
    void ensureInitialized(long now, Duration interval, double jitter) {
        if (nextSyncAtNanos == 0) {
            nextSyncAtNanos = now + jittered(interval, jitter);
        }
    }

    boolean tryAcquire() {
        return syncing.compareAndSet(false, true);
    }

    void reschedule(long now, Duration interval, double jitter) {
        nextSyncAtNanos = now + jittered(interval, jitter);
    }

    /** 失败退避：推进一个短间隔，避免中央故障时每个访问都重试 */
    void backoff(long now, Duration retryBackoff) {
        nextSyncAtNanos = now + retryBackoff.toNanos();
    }

    void release() {
        syncing.set(false);
    }
}
```

- 每个缓存名一个门控；`nextSyncAtNanos` 在**第一次访问**时由 `ensureInitialized` 设置，首次访问不触发同步；
- 到点后的**下一次访问**触发同步，并推进下一次时间；
- 没有访问就没有同步；
- 同一时刻只允许一个线程执行同步，避免多线程重复上报。

> 版本对账不需要给每条副本单独设门控（信号量在命名空间级）；热 key 同步仍然有自己的门控。两者可以由同一个访问事件串行触发。

### 5.4 状态机

`coordination=GLOBAL`（跨节点）：

```text
                     本地 freq >= localHotThreshold
   COLD ─────────────────────────────────────────────→ HOT
     ↑                                                   │
     │                                                   │ 中央 COOLING
     │                                                   ↓
     └──────────── cooldown 到期 ──────────────────── COOLING

   中央 HOT（补偿查询）也从 COLD 直接升 HOT
   中央 ACTIVE / UNKNOWN 保持现状
```

- `COLD`：不在 `hotKeys`，本地不保证有值；
- `HOT`：在 `hotKeys`，本地存储应尽量持有该 key；
- `COOLING`：已从 `hotKeys` 移除并在冷却期；本地条目已删；
- 冷却期结束回到 `COLD`；
- **本地 `maintain` 不产生 `HOT → COOLING`**；只有中央 `COOLING` 能触发。

`coordination=LOCAL`（单机）：

```text
COLD --freq >= localHotThreshold--> HOT
HOT  --idle > hotTtl 或 freq 低于本地冷阈值--> COOLING
COOLING --cooldown 到期--> COLD
```

单机模式没有中央裁决，允许本地降热；该模式不解决跨节点热点问题，仅用于单机与测试。

### 5.5 伪代码

```java
// 两层编排在本地读之前调用
boolean access(CacheKey key) {
    rotateIfNeeded();
    sweepCooldownOccasionally(now);               // 每 1024 次 access 清一条过期冷却项
    int freq = sketch.incrementAndEstimate(key);
    // 增量缓冲有界：只跟踪已热 key 与本地候选；满时丢低频尾部
    trackPendingDelta(key);

    HotEntry hot = hotKeys.get(key);
    if (hot != null) {
        hotKeys.put(key, hot.touch(now, freq)); // 更新 lastAccess
        lru.offer(key);
        return true;
    }

    if (isCooling(key)) {                          // 内部读时惰性删过期项
        return false;
    }

    if (freq >= spec.dynamic().localHotThreshold()) {
        promote(key, freq);   // 本地升热立即生效，不等中央
        return true;
    }

    return false;
}

// 两层编排在 access 之后、lookup 之前调用；只有到点才产生一次 I/O
void hotSync(CacheKey key) {
    if (spec.dynamic().coordination() != GLOBAL) return;
    hotSyncGate.ensureInitialized(now, spec.dynamic().syncInterval(), jitter);
    if (!hotSyncGate.due(now)) return;
    if (!hotSyncGate.tryAcquire()) return;

    int p = partitionOf(key, spec.dynamic().hotReportPartitions());
    Map<CacheKey, Long> batch = new LinkedHashMap<>();

    try {
        // 1) 该分区内已升热 key：有增量带增量，没有就带 0（纯状态查询）
        for (CacheKey k : hotByPartition.get(p)) {
            batch.put(k, takePendingDelta(k));
        }
        // 2) 该分区内的预选 key（可能是下一个热点）
        for (CacheKey k : candidateByPartition.get(p)) {
            batch.putIfAbsent(k, takePendingDelta(k));
        }
        // 3) 倾斜保护：超硬上限就按增量取 top-N，其余留到下一轮
        if (batch.size() > spec.dynamic().hotReportBatchMax()) {
            batch = topByDelta(batch, spec.dynamic().hotReportBatchMax());
            stats.hotReportDropped();
        }

        Map<CacheKey, HotKeyReport> reports = coordinator.reportAndStatusAll(namespace, batch);
        applyCentralStatus(reports);
        hotSyncGate.reschedule(now, spec.dynamic().syncInterval(), jitter);
        stats.hotSync(batch.size());
    } catch (RuntimeException e) {
        restorePendingDelta(batch);           // 整批回填，下一轮继续上报
        hotSyncGate.backoff(now, spec.version().retryBackoff());
        stats.hotSyncError();
    } finally {
        hotSyncGate.release();
    }
}

void applyCentralStatus(Map<CacheKey, HotKeyReport> reports) {
    for (Map.Entry<CacheKey, HotKeyReport> e : reports.entrySet()) {
        CacheKey key = e.getKey();
        HotKeyReport r = e.getValue();
        switch (r.status()) {
            case HOT -> {
                if (!hotKeys.containsKey(key) && !isCooling(key)) {
                    promote(key, r.windowCount());   // 补偿升热
                    stats.globalPromotion();
                }
            }
            case COOLING -> {
                if (hotKeys.containsKey(key)) {
                    demote(key);                      // 唯一权威降热（内部清 pending 与分区桶）
                    stats.globalDemotion();
                }
            }
            case ACTIVE, UNKNOWN -> { /* 保持现状，不降热 */ }
        }
    }
}

// 两层编排在拿到中央/回源值后调用
void put(CacheKey key, LocalEntry<V> entry, Duration ttl) {
    if (!hotKeys.containsKey(key)) {
        stats.dynamicPutDiscarded();
        // 冷 key 不落本地：写不计频次，也就不可能因为它“热”。
        // 需要 read-your-write 请用 STATIC（它本来就全量缓存）。
        return;
    }
    Duration effective = entry.kind() == NEGATIVE
            ? min(ttl, spec.dynamic().hotTtl(), spec.nullTtl() == null ? ttl : spec.nullTtl())
            : min(ttl, spec.dynamic().hotTtl());
    delegate.put(key, entry, effective);
}

// 时间驱动或显式调用；不做频次降热
void maintain() {
    rotateIfNeeded();
    cleanupCooldown();

    if (spec.dynamic().coordination() == LOCAL) {
        // 单机模式：允许本地按空闲/低频降热
        for (CacheKey key : hotKeys.keySet()) {
            HotEntry e = hotKeys.get(key);
            if (e == null) continue;
            if (now - e.lastAccessNanos() > spec.dynamic().hotTtl().toNanos()) {
                demote(key);
            }
        }
    }
    // GLOBAL 模式：降热只由 applyCentralStatus(COOLING) 触发
}
```

`promote`：

```java
void promote(CacheKey key, long freq) {
    int maxHotKeys = spec.dynamic().maxHotKeys();
    while (hotKeys.size() >= maxHotKeys) {
        CacheKey victim = pollLiveKeyFromLru();
        if (victim == null) break;
        HotEntry removed = removeHot(victim);   // 容量淘汰，不是语义降热
        if (removed != null) {
            delegate.evict(victim);             // 释放本地条目
        }
    }

    hotKeys.put(key, HotEntry.now(freq));
    lru.offer(key);
    // 下一次热同步点会带上这个 key 的增量（热点 key 必须上报）
    stats.promotion++;
}
```

- 容量满时优先淘汰"最久未访问"的热点 key；也可以按 `lastEstimate` 选择更冷的 key，但扫描成本高。v1 用近似 LRU，简单、O(1) 摊销。
- 被容量淘汰的热点 key 立即 `delegate.evict`：热点集合是本地准入的唯一依据，移出就应释放本地条目。该 key 若马上又被访问，需要重新升热；冷却期会抑制抖动。
- 容量淘汰计数为 `eviction`，不计入 `demotion`，避免指标把"内存管理"误读成"热度下降"。
- 降热 `demote` 与容量淘汰走同一套逻辑：移出热点集合时 `delegate.evict(key)`；若本地还没有这条条目，`evict` 是空操作。

### 5.6 参数调优

| 参数 | 调大 | 调小 | 建议 |
|---|---|---|---|
| `localHotThreshold` | 更难本地升热、本地更省内存 | 更容易升热、命中率更高 | 先按单节点 QPS 分布取 P95，再压测 |
| `window` | 统计更平滑、反应慢 | 反应快、抖动大 | 10s 起步 |
| `maxHotKeys` | 更多热点、内存更大 | 更省内存、可能误伤热点 | 按本地内存预算与 key 大小估算 |
| `hotTtl` | 热点驻留更久、可能更旧 | 更快回中央、更新鲜 | ≤ 中央 TTL；30–120s |
| `syncInterval` | 补偿查询更少、热度感知更慢 | 感知更快、中央 I/O 更多 | 1s 起步；与 `version.checkInterval` 对齐 |
| `hotReportPartitions` | 单次上报更小、抖动更低的 Redis | 分区更少、上报更集中 | 16 起步（2 的幂） |
| `hotReportBatchMax` | 单次覆盖快 | 单次更省 | 64；不超过 128（单 Lua 别超 400 次 redis.call） |
| —（监控项） | — | — | `dynamicPutDiscarded` 持续偏高 = 在用 `DYNAMIC` 做它不擅长的事（大量“写后读”），应改用 `STATIC` 或下调 `localHotThreshold` |
| `cooldown` | 升降热更稳、反应慢 | 反应快、易抖动 | ≥ 2 个 `window` |
| `sketchWidth` | 碰撞少、内存大 | 内存小、误升多 | 4096 起步；热点基数大时加倍 |
| `globalHotThreshold` | 更少 key 被判全局热 | 更多 key 进入本地 | 按集群 QPS 与热点集中度压测 |
| `globalCoolThreshold` | 降热更慢、热点驻留更久 | 降热更快、易抖动 | 约为 `globalHotThreshold` 的 1/4 |
| `global.clientEstimate` | 预选阈值更低、候选更多、上报压力大 | 预选阈值更高、可能漏掉全局热 key | **按实际部署实例数填**；填大漏报、填小浪费额度 |
| `candidateCapacity` | 能容纳更多候选 | 候选易被 top-B 挤掉 | 默认 = `maxHotKeys` |
| `demoteWindows` / `staleWindows` | 降热更保守 | 降热更快 | 3 起步 |

经验法则：

- `maxHotKeys` 先按"最热 1% key 覆盖多少请求"估算；
- `maxSize >= 1.25 * maxHotKeys`，给底层淘汰留余量；
- `hotTtl` 不要大于中央 TTL，否则本地会比中央更旧；
- `localHotThreshold` 只解决单节点热点；跨节点热点必须靠 `globalHotThreshold`；
- **评价 `DYNAMIC` 不要看全时段 `localHit / request`**：冷 key 本就不允许进本地，这个值必然低于 `STATIC`，拿它当目标会把 `DYNAMIC` 调成劣化版 `STATIC`。正确的两个口径是：
  - **尖峰时段（或 P99）的 `localProtectionRate`**：`DYNAMIC` 的价值在流量尖峰与热点集中时才体现，要看峰不看均值；
  - **单位内存削峰量** = `localHit / estimatedSize`：同为 1MB 本地内存，挡住了多少 L2 读。
- 判定标准：**`protectionRate`（业务能不能免于回源）接近 `STATIC` 而 `estimatedSize` 明显更低**，才算参数合理；只看命中率会误判。

### 5.7 跨节点协调

```text
cache4j-core:  HotKeyCoordinator SPI + HotKeyStatus
cache4j-local: DynamicLocalCache 调用 coordinator
cache4j-central: 内存协调实现（测试/单机）
cache4j-central-redis: Redis 窗口计数 + Lua reportAndStatus
```

`HotKeyStatus`：

```java
public enum HotKeyStatus {
    HOT,        // 集群窗口频次达到升热阈值
    ACTIVE,     // 有上报但未达升热阈值
    COOLING,    // 连续低活动或长时间无上报
    UNKNOWN     // 中央从未收到该 key 的上报
}
```

规则：

- `HOT`：升热或续期；
- `ACTIVE` / `UNKNOWN`：保持现状，不降热；
- `COOLING`：降热，唯一权威；
- 本地未升热的 key 也要参与上报与补偿查询（候选 top-K），否则中央永远看不到它；
- 已升热的 key **必须上报**，否则中央无法判断它是否该降热；
- 没有访问时不上报、不查询。

上报范围：

| 集合 | 是否上报 | 是否查询状态 |
|---|---|---|
| 已升热 key | 必须 | 必须 |
| 本地候选 top-K（未升热） | 是 | 是 |
| 其他冷 key | 否 | 否 |

> 上报的**时机**由分区决定：某个 key 是否在这一轮被上报，取决于"当前被访问的那个 key 是否与它同分区"，而不是它自己有没有被访问。所以上表的"必须"指的是"必须纳入分区桶"，而不是"每次访问都上报它"。

### 5.8 边界与退化

| 场景 | 行为 |
|---|---|
| 草图碰撞导致误升 | 最坏多占 `maxHotKeys` 个本地条目，不影响正确性 |
| 底层 Caffeine 淘汰了热点 key | `lookup` 未命中 → 查中央 → 下次 `put` 时若仍在热点集合则重新写入 |
| 热点集合满 | 淘汰最久未访问的热点 key，并清其本地条目；计为 `eviction`，不计 `demotion` |
| 冷却集合满 | 按最早到期淘汰；被挤掉的是即将到期的项，损失可忽略 |
| `maintain` 未及时调用 | 不影响正确性：草图旋转与冷却清理都在 `access` 路径惰性完成；`maintain` 只做整批清理，建议监控 `hotKeyCount` |
| 时钟回拨 | 统一用 `System.nanoTime()` 计算间隔，避免墙钟回拨 |
| 中央上报/查询失败 | 保持当前热点状态，不降热；增量保留，下次访问重试 |
| 中央长时间不可用 | 靠 `maxHotKeys` 与 `hotTtl` 兜底；期间不降热；健康检查标记降级 |
| 配置 `coordination=GLOBAL` 但无协调实现 | **启动失败**，不静默退化为单机 |
| 缓存关闭 | 清空热点/冷却/增量集合，关闭底层存储 |

> `DYNAMIC` 的正确性下限：**即使热键算法完全失效，也只是退化成"本地命中率低"或"本地多占一点内存"**，不会返回中央缓存中不存在的数据；值的正确性由版本机制（§4）保证。

---

## 6. 默认 HashMap 实现

### 6.1 定位

- 零第三方依赖，保证 `cache4j-local` 可独立使用；
- 适合小服务、测试、本地开发；
- 生产高并发/大容量建议 Caffeine。

### 6.2 结构

```text
HashMapLocalCache
├── Segment[16]
│   ├── ReentrantLock
│   ├── LinkedHashMap<CacheKey, TimedEntry<V>>(accessOrder=true)
│   └── TimedEntry<V> { LocalEntry<V> entry; long expireAtNanos; }
└── LongAdder stats
```

- `maxSize` 按段均分：`ceil(maxSize / 16)`；总量允许略超（每段独立淘汰）；
- `LinkedHashMap(accessOrder=true)` 提供段内 LRU；
- `get`：加锁 → 删除过期项 → 读取（`accessOrder` 自动移到队尾）；
- `put`：加锁 → 替换/插入 → 超过段容量则删除 eldest；
- `putIfAbsent`：同一把段锁内完成"检查过期 + 插入"，保证原子；
- `evict` / `clear`：按段操作；
- `estimatedSize`：各段 size 之和。

### 6.3 过期

- 写入时记录 `expireAtNanos = System.nanoTime() + ttl`；
- 读时惰性删除过期项；
- 可选定时清理（默认 60s）扫描各段，避免"只写不读"的 key 占内存；
- 不提供 `expireAfterAccess`；v1 只有写后过期。

### 6.4 取舍

| 优点 | 代价 |
|---|---|
| 零依赖、实现简单、行为可预测 | 分段 LRU 不是全局 LRU，命中率略低 |
| 段锁降低竞争 | 16 段固定，极端倾斜仍有竞争 |
| 内存占用可控 | 每条约 100+ 字节对象开销，大容量不如 Caffeine |

---

## 7. Caffeine 适配（`cache4j-local-caffeine`）

### 7.1 映射

```java
Caffeine.newBuilder()
        .maximumSize(spec.maxSize())
        .expireAfter(perEntryExpiry(spec.ttl()))
        .recordStats()
        .build();
```

- 用 Caffeine 的 `Expiry` 支持**每条 entry 不同的 TTL**（`put` 传入的 TTL 可能短于默认值）；
- `expireAfterWrite` 作为默认；v1 不启用 `expireAfterAccess`；
- `recordStats()` 把 Caffeine 的 hit/miss/eviction 映射到 `CacheStats`；
- `estimatedSize()` 直接用 Caffeine 的估算；
- 不改变 Caffeine 的 Window TinyLFU 淘汰策略；`DYNAMIC` 的热点门控在装饰器层完成。

### 7.2 注意

- Caffeine 的 `Expiry` 在 entry 创建/更新/读取时回调；实现要避免在回调里做重活。
- Caffeine 的 `maximumSize` 是全局上限，不是分段；与 `DYNAMIC` 的 `maxHotKeys` 配合时，建议 `maxSize >= 1.25 * maxHotKeys`。
- Caffeine 的统计计数与 cache4j 的统计口径不完全一致（例如 negative 不存在）；适配层只取 hit/miss/eviction/load 相关计数，负缓存由上层统计。

---

## 8. 校验与测试

### 8.1 启动校验

| 策略 | 规则 |
|---|---|
| `NONE` | 忽略 `maxSize` / `ttl` / `version`；非零配置记 WARN |
| `STATIC` | `maxSize > 0`；`ttl > 0`；`ttl <= central.ttl`（中央有限时）；`version.checkInterval > 0`；`0 <= version.jitter <= 0.5`；`version.retryBackoff > 0` |
| `DYNAMIC` | `maxSize > 0`；`ttl > 0`；`1 <= maxHotKeys <= maxSize`；`1 <= localHotThreshold <= 255`；`window > 0`；`hotTtl > 0` 且 `<= central.ttl`；`syncInterval > 0`；`cooldown >= 0`；`sketchWidth` 为 2 的幂且 `>= 1024`；`sketchDepth >= 2` |
| `DYNAMIC` + `GLOBAL` | 必须提供 `HotKeyCoordinator`；`global.window > 0`；`0 <= global.coolThreshold < global.hotThreshold`；`demoteWindows >= 1`；`staleWindows >= 1`；`global.clientEstimate >= 1`；`localHotThreshold >= 2`；派生 `U` 后恒有 `U < localHotThreshold`，但若 clamp 前的 `ceil(hotThreshold / clientEstimate) >= localHotThreshold` 记 WARN（候选带宽只剩 1）；**`cooldown >= demoteWindows × global.window`**（否则本地刚解冻、中央正好又判 `HOT`，形成慢速抖动） |

### 8.2 契约测试

- `put` / `lookup` / `putIfAbsent` / `evict` / `clear`（key 为 `CacheKey`，value 为 `LocalEntry`，含 `VALUE` / `NEGATIVE` 两种 kind）；
- TTL 到期（注入 `Clock`，不 `sleep`）；
- 超过 `maxSize` 后的淘汰；
- 并发 `putIfAbsent` 只有一个成功；
- `close` 幂等；
- `estimatedSize` 不超上限（允许分段误差）；
- 存储层按调用方给的 `LocalEntry` 直接覆盖（revision 比较在编排层的回填处已完成，存储层不重复判断）；
- 存储层**不自报业务命中率**：`storeHitCount` / `storeMissCount` 与门面的 `localHitCount` 无关，**不得相加**；
- **负副本**：`kind == NEGATIVE` 的条目能被 `lookup` 原样返回（不是 Miss），存入/淘汰/过期语义与 `VALUE` 一致。

### 8.3 版本专项（`STATIC` / `DYNAMIC`）

- 副本在 `checkInterval` 内命中本地，不产生中央 I/O；
- 到对账点后的下一次访问只产生一组（分批的）`HMGET`，只取版本不取数据值；
- 版本变化 → 副本被清除（下次访问回中央）；
- 中央缺字段（删除 / `clear`）→ 副本被清除；
- `expireAt` 已过 → 副本被清除 + 该 field 被 `HDEL`；
- 版本未变 → 副本保留，**不产生数据回填**；
- 对账失败 → 不改任何副本，按 `retryBackoff` 推迟下次；
- 同一命名空间并发到点只有一个线程执行对账；
- **无饥饿回归**：同 namespace 里 10 万 key 高频混访，某个低频 key 的副本也会在下一轮对账被发现。

### 8.4 `DYNAMIC` 专项

- Zipf 访问分布下，热 key 被升入本地；
- `hotKeys.size() <= maxHotKeys`；
- 冷 key 永不落本地；
- 本地频次未达阈值、中央返回 `HOT` 时，key 被补偿升热；
- 本地频次下降不会触发降热；只有中央 `COOLING` 才降热；
- 无访问时不产生上报/查询 I/O（fake coordinator 调用次数为 0）；
- 上报失败不改变热点状态，增量保留；
- 容量淘汰计为 `eviction`，不计入 `demotion`；
- 冷却期内不会反复升降；
- **冷却项自清**：过期项在 `isCooling` 读取时被删除；连续 1024 次 `access` 后，从未再被访问的过期冷却项数量下降；
- 冷却满时新增降热能挤掉一条即将过期的项（新冷却不丢）；
- **负副本**：`nullTtl > 0` 时，热点 key 的负副本会落本地并在 `min(hotTtl, nullTtl)` 后过期；`nullTtl == 0` 时本地不存负副本；
- **负 key 也能升热**：反复查询的不存在 key 达到阈值后正常进热点集合，`localNegativeHitCount` 随之增长；
- 频率草图内存在固定上限；
- 本地 TTL 到期后，热点 key 仍在热点集合中时会重新回填。

### 8.5 实现对比

同一套契约测试跑 `HashMapLocalCache` / `CaffeineLocalCache` / `DynamicLocalCache(两者)`，保证行为一致。
