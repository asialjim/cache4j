# cache4j 重构方案

> **状态**：方案稿（待评审），尚未动代码。
>
> **目标**：用一组更简洁的设计理念，把现有实现里"过度设计"的部分减下来。
>
> **约束**：
> 1. **对外行为不变** —— 现有 257 个用例是验收基线，任何一步都必须保持全绿；
> 2. **模块边界保留** —— `core` 不依赖 Caffeine / Redis / Spring，这是本库的核心卖点；
> 3. **本质复杂度不减** —— 对账竞态、单飞、热 key 精度这些是缓存本身的问题，简化结构不等于简化它们（见 §6）。

---

## 1. 现状量化

### 1.1 规模

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `cache4j-core` | 52 | **7 374** |
| `cache4j-local` | 8 | 1 560 |
| `cache4j-local-caffeine` | 3 | 317 |
| `cache4j-central` | 3 | 442 |
| `cache4j-central-redis` | 5 | 584 |
| `cache4j-spring` | 14 | 1 592 |
| `cache4j-spring-redis` | 5 | 1 055 |
| **main 合计** | **90** | **12 924** |
| 测试 | 43 | 7 962 |

### 1.2 复杂度热点（`cache4j-core` 内）

| 类 | 行数 | 判断 |
|---|---:|---|
| `DefaultTwoLevelCache` | **1 011** | 单类巨兽：策略分支 + 对账 + 单飞 + 回填 + 统计全塞在里面 |
| `DefaultCacheStrategyRepository` | 609 | |
| `KeyEncoders` | 546 | 五套编码器（canonical / identity / delegate / plain / tagged） |
| `LocalCacheSpec` | 517 | 四层嵌套 record（VersionSpec / DynamicSpec / GlobalSpec / Coordination） |
| `CacheLogger` | 391 | 主题枚举 + 限流 |
| `CacheStats` + `CacheCounters` + `NoopStats` | **802** | 统计三件套 |
| `CachedCacheStrategyRepository` | 277 | 门面 + 委托 + 双缓存 + 时钟注入 |
| `TextPolicyCodec` | 246 | |

**三个结论：**

1. **策略仓库一族 = 2 683 行，占 `core` 的 36%** —— 为了"策略可运维"这一个功能；
2. **统计 + 日志 + 编码器 = 1 739 行** —— 与缓存语义无关的"外围设施"；
3. **`DefaultTwoLevelCache` 一个类 1 011 行** —— 说明"双层编排"的职责全压在一个实现上，没有按机制拆开。

---

## 2. 重构目标：四条理念

### 2.1 一个接口贯穿三层

```java
LocalCache4j<K, V>   extends Cache4j<K, V>
CentralCache4j<K, V> extends Cache4j<K, V>
Cache4jImpl<K, V>    implements Cache4j<K, V>   // 只组合上面两个
```

现状是 `Cache` → `TwoLevelCache` → `LocalCache` / `CentralCache` 三个平行接口，加一个 1 011 行的组合实现。统一接口之后门面只需 ~150 行。

### 2.2 策略 = 实现类 + 运行时路由

```java
NoneLocalCache4j      // NONE
StaticLocalCache4j    // STATIC
DynamicLocalCache4j   // DYNAMIC
MixedLocalCache4j     // 按当前策略路由到上面三者之一
```

现状是"枚举 + `LocalCacheSpec` 嵌套参数 + `LocalCacheProvider.create(spec)` 分支"，策略在**装配期**定死，运行期改策略必须重启。

> **这是本次重构唯一的"新增能力"**：运行期切策略。其余都是减法。

### 2.3 一个策略用一个类表达

现状用 6 个类描述"一个缓存策略"：

| 类 | 职责 |
|---|---|
| `CacheStrategyDecl` | 代码里的声明 |
| `NamespacePolicy` | 解析后的生效值 + `Source` |
| `StoredPolicy` | 持久化记录（含 `configVersion` / `updatedBy` / `reason`） |
| `StrategyView` | 运维视图（含 `orphan` / `broken` / `undeclared`） |
| `PolicyResolver` | 三层解析（仓库 → 声明 → 内建默认） |
| `BuiltinDefaults` | 内建默认值 |

目标收敛成三个，各管一件事：

| 目标类 | 职责 |
|---|---|
| `CacheBlock` | 代码里的声明（namespace / 初始策略 / 用途说明） |
| `CacheStrategyProperty` | **配置载体**：扁平、只有包装类型与 String（见 §3 第 2 步） |
| `CacheStrategy` | 生效值：持 `Property` + 若干 `Supplier`（运行期可读最新值）+ 审计字段 |

`PolicyResolver` 的三层解析逻辑保留，但挪进 `CacheStrategy` 的构造过程；`StrategyView` 的运维标记（`orphan` / `broken` / `undeclared`）作为 `Administrator` 的视图字段，不再单独成类。

### 2.4 自举用自己的缓存实现

```java
// 目标形态（约 30 行）
public final class CacheStrategyCache {
    private final Cache4j<String, CacheStrategy> cache;
    private final CacheStrategyDataSource dataSource;

    public CacheStrategy namespaceOf(String namespace) {
        return cache.get(namespace, () -> dataSource.namespaceOf(namespace));
    }
}
```

现状 `CachedCacheStrategyRepository` 277 行（门面 + 委托 + 自缓存 + 双时钟注入）。

---

## 3. 分三步走

三步独立可交付，每步结束都必须 `mvn test` 全绿。

### 第 1 步：纯减法（零行为变化）

| 动作 | 说明 | 省 |
|---|---|---:|
| **统计全部删除** | `CacheStats` / `CacheCounters` / `CacheStatsSnapshot` / `NoopStats` 整个删掉，连同所有 `stats().xxx()` 调用点。核心跑通后再重新设计统计 | ~800 |
| 编码器收敛 | 只留 `plain`（默认）；删 `canonical` / `tagged` / `identity` | ~450 |
| 日志主题删掉 | `CacheLogger` 整体移除，改用 SLF4J，按 `[主题]-操作-详情` 组织文案 | ~390 |

**合计约 1 640 行。**

统计删除的影响面（实测）：main 12 个文件引用、测试 9 个文件引用。需要逐个摘掉计数语句，但**不改变任何控制流**——所以风险仍然很低，只是改动点分散。

> 统计删掉之后，"对账执行了几次""回填丢弃了几次"这类断言暂时没有载体。第 1 步的验收只能靠**行为断言**（例如"并发只触发一次对账"用打桩计数验证，不用库内统计）。

### 第 2 步：并结构（行为不变，需改测试）

| 动作 | 说明 | 省 |
|---|---|---:|
| 策略模型 6 类 → 3 类 | `CacheStrategyDecl` / `NamespacePolicy` / `StoredPolicy` / `StrategyView` / `PolicyResolver` / `BuiltinDefaults` 收敛为 `CacheBlock`（声明）+ `CacheStrategy`（生效值）+ `CacheStrategyProperty`（配置载体） | ~800 |
| 仓库读写分离 | `CachedCacheStrategyRepository` → `CacheStrategyCache`（读，自举）+ `CacheStrategyAdministrator`（管理面） | ~250 |
| 配置载体扁平化 | `LocalCacheSpec` 四层嵌套 record → `CacheStrategyProperty` | ~300 |

**约 1 350 行。运维能力（乐观锁 / 分页 / 视图标记）全部保留**，只是从"6 个类协作"变成"2 个类 + 1 个管理面 + 1 个配置载体"。

#### `CacheStrategyProperty` 的硬约束

> **字段类型只允许基础类型的包装类或 `String`。禁止出现 `Duration`、`List`、`Map` 或任何自定义对象。**

理由：这个对象要能被"配置中心 / 数据库 / 文件"直接映射，任何嵌套对象都会把序列化复杂度转嫁给使用方。

**`Duration` 的处理**：拆成"数值 + 时间单位"两个字段，由构造 `CacheStrategy` 时还原：

```java
Duration.of(property.getLocalTtl(), TimeUnit.valueOf(property.getLocalTimeUnit()))
```

时间单位按**层**给，不按字段给——一个 namespace 只有一个本地单位、一个中央单位：

| 层 | 单位字段 | 覆盖的字段 |
|---|---|---|
| 本地 | `localTimeUnit` | 本地 TTL、对账间隔、抖动、退避、热 key 窗口、热同步间隔、冷却时长、中央阈值窗口 |
| 中央 | `centralTimeUnit` | 中央 TTL、中央空值 TTL |

> 默认 `localTimeUnit = SECONDS`、`centralTimeUnit = MINUTES`（本地秒级、中央分钟级是最常见的组合）。

**计数型字段与时间单位无关**，永远按"次 / 个"解释：`hotThresholdQps` / `coolQps` / `globalHotQps` / `globalCoolQps` 是 **QPS（每秒）**，不随 `localTimeUnit` 变化；`reconcileBatchSize` / `maxHotKeys` / `hotWindowSlices` / `sketchWidthBits` 等同理。

```java
public class CacheStrategyProperty {

    // ---- 标识
    private String  namespace;
    private String  description;
    private String  localStrategy;          // NONE / STATIC / DYNAMIC

    // ---- 时间单位（按层给，见上）
    private String  localTimeUnit;          // 默认 SECONDS
    private String  centralTimeUnit;        // 默认 MINUTES

    // ---- 本地：容量与 TTL
    private Long    localMaxSize;
    private Long    localTtl;               // 单位 = localTimeUnit
    private Integer localNullTtlDivisor;    // 本地负副本 TTL = 中央空值 TTL / 该值

    // ---- 本地：版本对账（时间字段单位 = localTimeUnit）
    private Long    versionCheckInterval;
    private Long    versionJitter;
    private Long    versionRetryBackoff;
    private Integer reconcileBatchSize;
    private Integer reconcilePartitions;

    // ---- 本地：热 key 判定（时间字段单位 = localTimeUnit；QPS 恒按秒）
    private Long    hotThresholdQps;
    private Long    coolQps;
    private Long    hotWindow;
    private Integer hotWindowSlices;
    private Integer maxHotKeys;
    private Long    hotSyncInterval;
    private Long    hotCooldown;
    private String  coordination;           // LOCAL / GLOBAL
    private Integer sketchWidthBits;
    private Integer sketchDepth;
    private Integer hotReportPartitions;
    private Integer hotReportBatchMax;
    private Integer candidateCapacity;

    // ---- 本地：GLOBAL 协调阈值（时间字段单位 = localTimeUnit）
    private Long    globalWindow;
    private Long    globalHotQps;
    private Long    globalCoolQps;
    private Integer globalDemoteWindows;
    private Integer globalStaleWindows;
    private Integer globalClientEstimate;

    // ---- 中央（时间字段单位 = centralTimeUnit）
    private Long    centralTtl;
    private Long    centralNullTtl;
    private Boolean failOpen;
}
```

`null` 一律表示"用内建默认值"，由构造 `CacheStrategy` 时补齐——配置中心只需要存**改过的字段**。

### 第 3 步：换内核（高风险，收益最大）

| 动作 | 说明 | 省 |
|---|---|---:|
| 统一接口 | `Cache` / `TwoLevelCache` / `LocalCache` / `CentralCache` → `Cache4j` + `LocalCache4j` + `CentralCache4j`；**`TwoLevelCache` 直接删除，不留兼容适配器** | ~650 |
| 拆分巨类 | `DefaultTwoLevelCache` 1 011 行 → `Cache4jImpl`（~150 行）+ `AbstractVersionLocalCache4j`（对账/回填收敛） | — |
| 单飞独立 | `SingleFlight` 成为一等契约（见 §4） | — |

**换来运行期切策略**（不重启），以及"同一份对账逻辑只写一次"。

> 这一步必须把 §6 的全部验收项搬过去当交付条件，缺一项就不算完成。

## 4. 机制落点

| 机制 | 落点 | 说明 |
|---|---|---|
| 本地读写 / 容量 / TTL | `LocalCache4j` 各实现 | |
| **对账** | `VersionLocalCache4j.reconcile()` | 持中央缓存，**调用即对账**（见下） |
| 单 key 版本 | `VersionLocalCache4j.version(key)` | 供回填校验，不是对账主路径 |
| **回填竞态** | `VersionLocalCache4j.backfillIfNewer(...)` | 版本仍等于读到的版本才回填 |
| 负缓存的本地 TTL | 由 `CacheStrategyProperty` 派生（`centralNullTtl / divisor`） | 写负副本时使用派生值 |
| **热 key 判定 / 升降热** | `DynamicLocalCache4j` | 依赖收窄为 `HotKeyCoordinator` |
| **DYNAMIC 写准入** | `DynamicLocalCache4j.put` | 冷 key 不占本地（hotkey 统计的是读频次） |
| **单飞** | `SingleFlight`（独立契约，见下） | 不由 `get` 隐式承担 |
| 版本产生（时间戳） | 中央缓存 | 本地只读版本，不自增 |

### 4.1 对账：调用即对账，禁止两段式

```java
/**
 * 到点则当场对账后返回；未到点直接返回。
 *
 * <p>调用方<strong>不需要</strong>、也<strong>不允许</strong>先判断"该不该对账"再调用——
 * 判断与执行必须封在实现内部，否则每个调用点都要复制一遍信号量逻辑，早晚不一致。</p>
 *
 * @return 本次是否真的执行了对账（仅供观测，不用于控制流）
 */
boolean reconcile();
```

对账本身仍然是**批量**的：

```text
一次对账 = HMGET <ns>:__rev <本地已知的全部 key>     ← 一个 namespace 一次往返
```

逐 key 查版本会让中央 QPS 随本地命中率上升而暴涨，这是现有实现的刻意设计（决策 D21），不得退化。

### 4.2 单飞：独立契约，因为它是"唯一写者"语义

单飞不只是"合并请求"，它表达的是**同一个 key 在任一时刻只有一个写者**：

- **进程角度**：同一 key 只有一个线程执行 loader 并写缓存，其余线程等它的结果；
- **集群角度**：同一 key 最终只有一份值落中央（由中央的原子写保证），不会出现两个节点各写一份互相覆盖。

因此它不该藏在 `get(key, loader)` 的语义里，而应是一个显式契约：

```java
public interface SingleFlight<K, V> {

    /**
     * 同一 key 在进程内只有一个线程执行 loader 并写缓存；其余线程等待并复用结果。
     *
     * <p>约定：</p>
     * <ul>
     *   <li>loader 抛异常时<strong>不缓存异常</strong>，本次所有等待者收到同一异常，下次重新尝试；</li>
     *   <li>loader 返回 null 视为"确认不存在"，负结果同样被合并（不能因为 null 就击穿）；</li>
     *   <li>不同 key 之间完全并行，不互相阻塞。</li>
     * </ul>
     *
     * @return 加载结果（三态，与 lookup 一致）
     */
    Lookup<V> once(K key, Supplier<V> loader);
}
```

放在 core，由门面组合：`本地 miss → 中央 miss → singleFlight.once(key, loader)`。

独立之后有三个好处：可单测、可替换（要跨节点保护就换分布式锁实现）、以及**不与 `get` 的返回类型纠缠**（`once` 返回 `Lookup`，天然带三态，负缓存不会被压成 `null`）。

**不挂在 `Cache4j` 接口上**。`Cache4jImpl` 内部持有一个 `SingleFlight` 供自己的 `get` 使用；框架适配层（如 Spring 的 `sync = true`）需要更精细的控制时，**自己注入 `SingleFlight` 并显式组合**：

```java
// cache4j-spring：SpringCacheAdapter
public <T> T get(Object key, Callable<T> loader) {
    Lookup<Object> hit = cache.lookup(key);
    if (hit.isHit()) return (T) hit.optional().orElseThrow();
    // 由适配层决定如何包装 loader、如何处理异常，single-flight 只负责"合并"
    return singleFlight.once(key, () -> callAndStore(key, loader)).optional().orElse(null);
}
```

这样 `Cache4j.get` 与框架层的 `sync` 语义互不绑死：前者是"便捷方法"，后者是"显式组合"。

### 4.3 回填必须 CAS

```text
线程 A：中央读到 v1 → 准备回填
线程 B：写入 v2（中央 + 本地）
线程 A：回填 v1  ← 旧值覆盖新值，无任何报错
```

这是对账之外最容易被漏掉的竞态，只在并发下出现。必须进接口，否则没人会实现。

## 5. 接口草案

```java
// ---------- 顶层：三层共用
public interface Cache4j<K, V> {
    CacheStrategy getCacheStrategy();
    String name();
    Cache4jKey key(K key);

    Lookup<V> lookup(K key);                      // 三态：Hit / Negative / Miss
    V get(K key, Supplier<V> valueLoader);        // 内部经 SingleFlight 合并
    void put(K key, V value);
    boolean putIfAbsent(K key, V value);
    void evict(K key);
    void clear();
}

// ---------- 本地：纯本地能力
public interface LocalCache4j<K, V> extends Cache4j<K, V> {
}

// ---------- 本地：有版本概念的子集（STATIC / DYNAMIC），NONE 不实现
public interface VersionLocalCache4j<K, V> extends LocalCache4j<K, V> {

    long version(K key);

    /** 本地已知的全部 key —— 对账输入，载荷跟 maxSize 走 */
    Set<K> localKeys();

    /** 调用即对账：到点则执行，否则直接返回。调用方不做判断。 */
    boolean reconcile();

    /** 版本仍等于读到的版本才回填；否则丢弃 */
    boolean backfillIfNewer(K key, V value, long readRevision);
}

// ---------- 本地：抽掉对账与版本，子类只管"本地怎么存"
public abstract class AbstractVersionLocalCache4j<K, V> implements VersionLocalCache4j<K, V> {

    protected final CentralCache4j<K, V> central;   // 只用于读版本
    protected final CacheStrategy strategy;          // 含 checkInterval / jitter
    protected final SingleFlight<K, V> singleFlight;

    private volatile long nextSyncAtNanos;
    private final AtomicBoolean syncing = new AtomicBoolean();

    @Override
    public boolean reconcile() { /* 信号量 + 批量 + 逐条 evict */ }

    protected abstract LocalEntry<V> localEntry(K key);
    protected abstract void localEvict(K key);
    protected abstract Set<K> localKeys0();
}

// ---------- 本地：热 key 只在这里
public interface DynamicLocalCache4j<K, V> extends VersionLocalCache4j<K, V> {
}

// ---------- 中央：版本产生 + 原子写
public interface CentralCache4j<K, V> extends Cache4j<K, V> {
    Cache4jKeyVersion keyVersion(K key);
}

// ---------- 单飞：唯一写者语义，独立契约
public interface SingleFlight<K, V> {
    Lookup<V> once(K key, Supplier<V> loader);
}
```

层次关系：

```text
Cache4jImpl  ──持有──►  MixedLocalCache4j  ──路由──►  NoneLocalCache4j
       │                      │                     StaticLocalCache4j   （extends AbstractVersionLocalCache4j）
       │                      │                     DynamicLocalCache4j  （extends AbstractVersionLocalCache4j，持 HotKeyCoordinator）
       └──持有──►  CentralCache4j ──持有──►  SingleFlight
```

`NoneLocalCache4j` 只实现 `LocalCache4j`（没有版本、不需要对账）；`StaticLocalCache4j` / `DynamicLocalCache4j` 继承 `VersionLocalCache4j`。

`DynamicLocalCache4j` 的实现持有 **`HotKeyCoordinator`**（不是 `CentralCache4j`）—— 后者的 `put/evict/clear` 能力它不该有。

## 6. 不能减的复杂度（本质复杂度清单）

这些是缓存本身的问题，简化结构不得牺牲它们。**每一项都要有对应的验收用例**：

| 项 | 现状载体 | 验收要点 |
|---|---|---|
| 对账竞态 | `syncing` CAS + `nextSyncAtNanos` | 并发访问只触发一次对账 |
| 回填竞态 | `backfillDiscarded` | 旧版本回填被丢弃且计数 |
| 单飞 | `inflight` map | 同 key 并发只回源一次，失败不缓存异常 |
| 热 key 精度 | 16 片滑动窗口 + 精确计数 | 达阈值立即升热；不因窗口切片误判 |
| 候选预选 | Count-Min Sketch（16 位） | 宁多勿少，不漏热点 |
| 负缓存 TTL 派生 | `centralNullTtl / divisor` | 本地负副本用派生值，不直接用中央值 |
| 策略乐观锁 | `configVersion` CAS | 版本冲突返回 `CONFLICT`，不覆盖 |
| 时钟一致性 | 同层同一套 `nanoClock` / `milliClock` | 混用会导致"对账永远不到点" |
| 未声明 namespace | 告警 + 内建默认 + 可见计数 | 不静默失败，也不阻塞启动 |
| 播种后重读 | `bootstrap` 后重读数据源 | 否则 `configVersion` 不一致，`change` 永远冲突 |

---

## 7. 风险与验收

### 7.1 每步的验收标准

| 步骤 | 验收 |
|---|---|
| 第 1 步 | 删除后 `mvn test` 全绿。统计删掉后失去载体的断言**直接清理**；只保留两个必要计数：`AbstractVersionLocalCache4j` 内的 `AtomicLong reconcileCount`（或直接读 `reconcile()` 的返回值）、以及 `backfillIfNewer` 的返回值——都够断言用，不需要统计框架 |
| 第 2 步 | 策略仓库相关用例全绿；`Administrator` 的读走缓存、写直连数据源、变更后 evict 缓存 |
| 第 3 步 | §6 全部十项都有对应用例；运行期改策略后**下一次访问生效**且无需重建缓存对象 |

### 7.2 关于 `cache4j-2`

`../cache4j-2` 是同一仓库的一个实验分支（`develop`），当前 21 个文件 / 856 行、**零测试**，用于验证"策略即实现类 + 运行时路由 + 自举用自己的缓存"这套理念是否可行。

**它的定位是设计参考，不是代码来源**：

- 可取：接口分层（`Cache4j` / `LocalCache4j` / `VersionLocalCache4j`）、策略路由、自举方式；
- 不可取：版本内联进本地 key（会让"本地命中就不问中央"，跨节点失效退化成等 TTL）；
- 待补：本地 `lookup` 的版本校验、DYNAMIC 写准入、回填 CAS、负缓存三态。

它的 856 行之所以"简洁"，很大程度是还没走到 §6 那些边界。**重构不得以"照抄它"为路径**，只能以"用它的结构，配现有的语义"为路径。

---

## 8. 决策记录

| # | 问题 | 决策 |
|---|---|---|
| R1 | 统计去留 | **先全部删除**（含接口与所有调用点）。核心跑通后再重新设计，避免"为了统计而统计"的结构复杂度 |
| R2 | `canonical` / `tagged` / `identity` 编码器 | **删除**，只留 `plain`（默认） |
| R3 | `CacheLogger` 主题枚举 | **删除**，直接用 SLF4J，按 `[主题]-操作-详情` 组织文案。**日志级别只用 `debug` / `warn` / `error`，不用 `info`**——现有的 13 处 `info` 一律降为 `debug`，后续由使用者按需手动调整。**不保留限流**（现有 16 处 `warnThrottled` 变普通 `warn`） |
| R4 | namespace 配置载体 | 新增 **`CacheStrategyProperty`**；字段只允许包装类型或 `String`；`Duration` 拆成"数值 + 时间单位"，**时间单位按层给**（`localTimeUnit` / `centralTimeUnit`），计数与 QPS 字段不受单位影响；无需 `List` / `Map` |
| R5 | `TwoLevelCache` 是否保留兼容 | **直接删除**，不留适配器 |
| R6 | 对账契约 | **单方法、调用即对账**；判断与执行封在实现内部，禁止调用方两段式。返回值供观测（`boolean`，后续接统计可用） |
| R7 | 单飞归属 | **独立契约 `SingleFlight`**（唯一写者语义）；**不挂到 `Cache4j` 接口**，框架适配层按需自行注入并显式组合 |
| R8 | 统计删除后的断言 | 失去载体的断言**直接清理**；必要计数用 `AtomicLong` + 方法返回值，不恢复统计框架 |
| R9 | `CacheStrategyProperty` 的"未设置" | `null` 表示"用内建默认"，够用 |
| R10 | 第 3 步迁移方式 | **直接切换**，不做新旧并行开关 |

### 开工前只剩一件需要确认的事

`AbstractVersionLocalCache4j` 里的 `AtomicLong reconcileCount` 是否要暴露 getter。倾向于**不暴露公共 API**（`reconcile()` 的返回值已够断言），仅在同包内可见；第 3 步之后如果确实需要，再作为统计的一部分重新设计。
