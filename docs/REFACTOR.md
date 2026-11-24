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

目标收敛成两个：`CacheBlock`（声明）+ `CacheStrategy`（生效值，参数用 `Supplier` 表达，审计字段作为可选内嵌）。

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

| 动作 | 省 |
|---|---:|
| 统计三件套 → 只留 `CacheStats` 接口 + `NoopStats`（~30 行），具体计数实现移出 core | ~700 |
| `KeyEncoders` 五套 → 留 `plain`（默认）+ `delegate`，删除 `canonical` / `tagged` / `identity` | ~400 |
| `CacheLogger` 主题枚举 → 直接用 SLF4J，格式约定写进文档 | ~350 |

**约 1 450 行；不改任何逻辑分支，只删不用的东西。**

> 删除前要确认调用点：`canonical` / `tagged` 目前只有测试在用；`CacheLogger` 的主题枚举只有日志格式用途。

### 第 2 步：并结构（行为不变，需改测试）

| 动作 | 省 |
|---|---:|
| 策略模型 6 类 → `CacheBlock` + `CacheStrategy`（审计字段内嵌） | ~800 |
| `CachedCacheStrategyRepository` → `CacheStrategyCache`（读，自举）+ `CacheStrategyAdministor`（管理面） | ~250 |
| `LocalCacheSpec` 四层 record → 各策略自己的构造参数 | ~300 |

**约 1 350 行。运维能力（乐观锁 / 分页 / 视图标记）全部保留**，只是从"6 个类协作"变成"2 个类 + 1 个管理面"。

管理面与读路径的关系（组合，不是继承）：

```text
CacheStrategyCache          持有 Cache4j<String, CacheStrategy>
      ↑ 持有
CacheStrategyAdministor     持有 CacheStrategyCache + CacheStrategyDataSource
      · 读：走 CacheStrategyCache（命中缓存）
      · 写：直接 dataSource + 变更后 evict CacheStrategyCache
```

### 第 3 步：换内核（高风险，收益最大）

| 动作 | 省 |
|---|---:|
| `TwoLevelCache` + `LocalCache` + `CentralCache` → `Cache4j` 统一接口 + `MixedLocalCache4j` | ~650 |
| `DefaultTwoLevelCache` 1 011 行 → `Cache4jImpl` ~150 行 + `AbstractVersionLocalCache4j`（对账/回填收敛） | — |

**换来运行期切策略**（不需要重启），以及"同一份对账逻辑只写一次"。

> 这一步必须把 §6 的全部验收项搬过去当交付条件，缺一项就不算完成。

---

## 4. 机制落点

| 机制 | 落点 | 说明 |
|---|---|---|
| 本地读写 / 容量 / TTL | `LocalCache4j` 各实现 | |
| **对账（批量）** | `VersionLocalCache4j.reconcileIfDue()` | 持中央缓存，一次 `HMGET` 覆盖整个 namespace |
| 单 key 版本 | `VersionLocalCache4j.version(key)` | 供回填校验，**不是**对账主路径 |
| **回填竞态** | `VersionLocalCache4j.backfillIfNewer(...)` | 版本仍等于读到的版本才回填 |
| 负缓存的本地 TTL | 由 `CacheStrategy` 派生（`centralNullTtl / divisor`） | 写负副本时使用派生值 |
| **热 key 判定 / 升降热** | `DynamicLocalCache4j` | 依赖收窄为 `HotKeyCoordinator` |
| **DYNAMIC 写准入** | `DynamicLocalCache4j.put` | 冷 key 不占本地（hotkey 统计的是读频次） |
| **单飞** | `CentralCache4j.get(key, loader)` | loader 只在这一层被合并 |
| 版本产生（时间戳） | 中央缓存 | 本地只读版本，不自增 |

### 4.1 三条不能含糊的契约

**① 对账必须是批量的**

```text
一次对账 = HMGET <ns>:__rev <本地已知的全部 key>     ← 一个 namespace 一次往返
```

逐 key 查版本会让中央 QPS 随本地命中率上升而暴涨。这条在现有实现里是刻意设计（决策 D21），重构不得退化。

**② 单飞返回值的三态**

`V get(K, Supplier<V>)` 无法区分"负命中"与"未命中"，穿透保护会在 `get` 路径上失效。两个可选：

- **A（推荐）**：`get` 返回 `Lookup<V>`；
- **B**：`get` 保留返回 `V`，但内部保证"负命中时直接返回 null 且不调 loader"，并把这条写进契约。

**③ 回填必须 CAS**

```text
线程 A：中央读到 v1 → 准备回填
线程 B：写入 v2（中央 + 本地）
线程 A：回填 v1  ← 旧值覆盖新值，无任何报错
```

这是对账之外最容易被漏掉的竞态，只在并发下出现。必须进接口，否则没人会实现。

---

## 5. 接口草案

```java
// ---------- 顶层：三层共用
public interface Cache4j<K, V> {
    CacheStrategy getCacheStrategy();
    String name();
    Cache4jKey key(K key);

    Lookup<V> lookup(K key);                      // 三态：Hit / Negative / Miss
    V get(K key, Supplier<V> valueLoader);        // 见 §4.1 ②
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

    /** 到点才执行，内部用 namespace 级信号量 + CAS 防止并发重复对账 */
    boolean reconcileIfDue();

    /** 版本仍等于读到的版本才回填；否则丢弃并计数 */
    boolean backfillIfNewer(K key, V value, long readRevision);
}

// ---------- 本地：抽掉对账与版本，子类只管"本地怎么存"
public abstract class AbstractVersionLocalCache4j<K, V> implements VersionLocalCache4j<K, V> {

    protected final CentralCache4j<K, V> central;   // 只用于读版本
    protected final CacheStrategy strategy;          // 含 checkInterval / jitter

    private volatile long nextSyncAtNanos;
    private final AtomicBoolean syncing = new AtomicBoolean();

    @Override
    public boolean reconcileIfDue() { /* 信号量 + 批量 + 逐条 evict */ }

    protected abstract LocalEntry<V> localEntry(K key);
    protected abstract void localEvict(K key);
    protected abstract Set<K> localKeys0();
}

// ---------- 本地：热 key 只在这里
public interface DynamicLocalCache4j<K, V> extends VersionLocalCache4j<K, V> {
}

// ---------- 中央：单飞 + 版本产生
public interface CentralCache4j<K, V> extends Cache4j<K, V> {
    Cache4jKeyVersion keyVersion(K key);
}
```

`StaticLocalCache4j` / `DynamicLocalCache4j` 继承 `VersionLocalCache4j`；`NoneLocalCache4j` 只实现 `LocalCache4j`（没有版本、不需要对账）。

`DynamicLocalCache4j` 的实现持有 **`HotKeyCoordinator`**（不是 `CentralCache4j`）—— 后者的 `put/evict/clear` 能力它不该有。

---

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
| 第 1 步 | 删除后 `mvn test` 257 全绿；无新增 `@Deprecated` 公共方法 |
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

## 8. 待定问题

1. **统计去留**：`CacheStats` 保留接口 + `NoopStats`，具体计数实现（`CacheCounters`）移出 core 是否可接受？现有 Spring 适配层依赖它做暴露。
2. **`canonical` / `tagged` 编码器**：删除会影响"类型安全"能力，是否有外部使用者？（当前仅测试引用）
3. **`CacheLogger` 的主题枚举**：删掉后日志格式靠文档约定，是否会影响现有的 `[主题]-子过程-信息` 检索习惯？
4. **`LocalCacheSpec` 的替代形式**：改成各策略的构造参数后，"统一描述一个 namespace 的配置"由谁承担——`CacheStrategy` 还是新的 `CacheBlockSpec`？
5. **第 3 步的迁移方式**：`TwoLevelCache` 接口是删掉还是保留为 `Cache4j` 的适配器（给已有使用者缓冲）？
