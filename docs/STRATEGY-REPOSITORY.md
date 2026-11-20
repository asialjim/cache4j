# 缓存策略仓库契约 & 仓库管理契约

> 状态：**契约草案，未实现**。
> 上游：`STRATEGY.md`（声明与发现：枚举三字段、`META-INF/services` 与 Spring 扫描、转换链路、优先级、运行时切换机制）。
> 下游：`DESIGN.md` / `LOCAL.md` / `CENTRAL.md`（缓存运行时本身）。
> 本文只定"仓库里有什么、谁能改、怎么改、改完怎么保证一致"，不讨论缓存读写路径。

## 0. 定位

| 组件 | 职责 | 不负责 |
|---|---|---|
| **声明**（`CacheStrategyDecl`） | 开发者表达"要一个叫什么、初始什么策略、干什么用"的缓存 | 任何参数 |
| **仓库门面**（`CacheStrategyRepository`，§1–§7） | 持有**生效配置**，是"当前这个 namespace 到底怎么配"的唯一权威；**对接本套缓存体系**（本地 + 中央两层） | 缓存实例的创建与读写（业务侧） |
| **仓库数据源**（`CacheStrategyDataSource`，§4） | 提供策略的**权威持久值与写入**；**不对接**本套缓存体系（纯 KV，不感知版本/两层/TTL） | 缓存、版本、一致性（由门面负责） |
| **管理**（本文 §8–§15） | 给出变更入口、校验、审计、观测 | 鉴权（使用方的 HTTP 层负责） |

**核心不变量（贯穿全文）**

1. 仓库是生效值的**唯一权威**；声明值只作初始兜底与审计对照。
2. 生效值解析顺序：**仓库 → 声明 → 内建默认**；三层都缺才算错误。
3. **没有仓库也能跑**：不实现任何仓库实现时，声明 + 内建默认即可装配，不报错。
4. **不提供 reset**：要回退就再改一次，审计留痕优先于"一键还原"。
5. **无后台线程、无广播**：仓库自身不建线程也不推送变更；传播由仓库自用缓存的版本对账承担（§2.4.4，≤5s）；
6. **仓库自举**：仓库门面本身就是缓存体系里的 `cache4j.strategy` 命名空间，`STATIC` 策略，
   禁止 `NONE`/`DYNAMIC`（§2.4.3）；自身缓存参数来自**启动配置**，改需重启（§2.4.6）。
7. **两层分工**：门面对接缓存体系，**数据源不对接**；开发者只需实现数据源（§4）。
8. **不做半应用**：任何一步失败，生效值不变。

---

# 第一部分：仓库契约（Repository Contract）

## 1. SPI 全景

```java
/**
 * 仓库门面：进程内生效配置的唯一权威，内部对接本套缓存体系（§2.4）。
 * 数据源（可选）通过工厂注入，见 §4.1。
 */
public interface CacheStrategyRepository {

    // ---------- 启动期 ----------

    /**
     * 登记一条声明（来自枚举）。
     *
     * <p><strong>仓库已有该 namespace 的策略时，本方法必须直接跳过</strong>：不登记、不覆盖、
     * 不改动已有信息（仓库是权威，代码声明不得回写已存在的配置）。跳过是幂等行为，
     * 只记 debug 日志，不告警。</p>
     *
     * <p>仅当两个<strong>声明</strong>（不同枚举 / 枚举与程序化定义）争抢同一个且仓库里还没有的
     * namespace 时，才抛 {@code CacheException} 冲突。</p>
     */
    void declare(CacheStrategyDecl decl, DeclSource source);

    /** 登记完毕，解析并冻结初始生效值。返回被解析出的 namespace 列表（保序）。 */
    List<String> bootstrap();

    // ---------- 读取（幂等、无副作用） ----------

    /** @return 生效策略；未登记返回 empty */
    Optional<NamespacePolicy> find(String namespace);

    /** @return 全部生效策略（保序：按 bootstrap 解析顺序） */
    List<NamespacePolicy> all();

    // ---------- 变更（管理侧唯一入口） ----------

    /**
     * 提交一次变更。见 §12。
     *
     * <p>不需要广播：变更由中央条目自身承载，其他节点通过仓库自用缓存的**版本对账**
     * （`version.checkInterval = 5s`）在 5 秒内看到新值。见 §2.4。</p>
     */
    ChangeResult change(StrategyChange change);
}
```

**契约约束**

- `find` / `all` **必须无副作用**（不得因读而创建、修复或写回任何状态）；
- `declare` / `bootstrap` / `change` **必须线程安全**；同一 namespace 的变更串行生效；
- 所有方法**不得抛受检异常**；失败一律 `CacheException`；
- **没有批量版本接口 / `refresh` / 广播**：变更传播完全由仓库自用缓存的版本对账承担（§2.4）；
- 中央不可用时 `find` 走缓存的降级路径（返回本地副本或未命中），**不得**改动生效值（见 §6）。

## 2. 数据模型

### 2.1 `NamespacePolicy`：一个 namespace 的生效配置

| 字段 | 类型 | 约束 | 默认来源 |
|---|---|---|---|
| `namespace` | `String` | `[A-Za-z0-9._:-]`，长度 ≤ 64 | 声明 |
| `description` | `String` | **非空**（`trim()` 后） | 声明；运维可改写 |
| `strategy` | `LocalStrategy` | 非空 | 仓库；缺省用声明值 |
| `maxSize` | `long` | > 0；`NONE` 忽略 | 内建默认 1 000 |
| `localTtl` | `Duration` | > 0；本地**非空副本**存活；`NONE` 忽略 | 内建默认 30s |
| `localNullTtlDivisor` | `int` | ≥ 1；本地空值副本 = `min(localTtl, centralNullTtl / N)` | 内建默认 10 |
| `version` | `VersionSpec` | 见 §2.2 | 内建默认 |
| `dynamic` | `DynamicSpec` | 仅 `DYNAMIC` 有意义；其它策略为 `null` | 内建默认 |
| `centralTtl` | `Duration` | > 0；中央**非空**存活 | 内建默认 30m |
| `centralNullTtl` | `Duration` | ≥ 0；`ZERO` = 不缓存空值 | 内建默认 30s |
| `failOpen` | `boolean` | — | 内建默认 `true` |
| `declaredStrategy` | `LocalStrategy` | 声明值，**只读**，审计用 | 声明 |
| `configVersion` | `long` | ≥ 1，单调递增 | **数据源**生成（不是缓存层） |
| `updatedAtMillis` / `updatedBy` / `reason` | — | 审计元数据 | 变更时写入 |
| `source` | `Source` | `DECLARED` / `REPOSITORY` / `DEFAULT` | 解析结果 |

**禁止出现在模型里的东西**（刻意的边界）：

- ❌ 本地空值 TTL 的绝对值（只能通过 `localNullTtlDivisor` 间接配）；
- ❌ 档位（`SMALL/LARGE`）；
- ❌ 中央连接、序列化方式、key 编码格式；
- ❌ `reset` 目标（没有 reset，见 §9.6）。

### 2.2 `VersionSpec`

| 字段 | 类型 | 默认 | 约束 |
|---|---|---|---|
| `checkInterval` | `Duration` | 5s | > 0（"时间信号量"窗口） |
| `jitter` | `Duration` | 100ms | `0 <= jitter < checkInterval` |
| `retryBackoff` | `Duration` | 200ms | > 0；运行时取 `max(backoff, checkInterval)` |
| `reconcileBatchSize` | `int` | 1 000 | > 0 |
| `reconcilePartitions` | `int` | 16 | 2 的幂 |

### 2.3 `DynamicSpec`（仅 `DYNAMIC`）

| 字段 | 默认 | 约束 |
|---|---|---|
| `hotThresholdQps` | 100 | ≥ 1（**QPS 语义**） |
| `window` | 10s | > 0（滑动窗口长度） |
| `hotWindowBuckets` | 10 | ≥ 1（每桶 `window / buckets`） |
| `maxHotKeys` | 1 000 | ≥ 1 |
| `candidateCapacity` | 1 000 | ≥ 1 |
| `cooldown` | 30s | ≥ 0；`GLOBAL` 下 ≥ `demoteWindows × window` |
| `hotReportPartitions` | 16 | 2 的幂 |
| `hotReportBatchMax` | 16 | ≥ 1 |
| `coordination` | `LOCAL` | `LOCAL` / `GLOBAL` |
| `global` | `null` | `GLOBAL` 时必填 |

> `hotTtl` **不存在**：DYNAMIC = STATIC + 准入判定，热副本的 TTL 与 `localTtl` 共用
> （见 `STRATEGY.md` §4.6）。同理 `localNullTtl` 不单独存在（见 §2.1）。

## 2.4 仓库自举：门面自己就是一个 `STATIC` 缓存

**门面不另建存储：它自己就是一个缓存实例。** 这条自我约束把"策略的读取"
降级成"缓存读写"，于是版本号、TTL、跨节点一致、降级全部由既有机制承担；
而"权威持久值"这一职责交给数据源（§4），两者严格分层。

### 2.4.1 仓库自身缓存的固定策略

| 项 | 固定值 | 为什么 |
|---|---|---|
| `namespace` | `cache4j.strategy`（常量） | 与业务 namespace 隔离；`clear(namespace)` 不得波及 |
| 本地策略 | **`STATIC`（禁止 `NONE` / `DYNAMIC`）** | 见 2.4.3 |
| `version.checkInterval` | **5s** | **这就是"变更在多节点上生效的最大延迟"**，取代广播 |
| `maxSize` | 1 000 | 策略条数与 namespace 数同量级 |
| 本地非空 TTL | **30m** | 策略极少变，本地副本可以活得久 |
| 中央非空 TTL | **3d** | 中央是权威副本，缓启动/长稳 |
| 中央空值 TTL | **5m** | "该 namespace 没有策略"也被缓存，避免穿透 |
| `failOpen` | `true` | 中央抖动时读本地副本，不阻塞业务启动 |

### 2.4.2 数据形态

| 项 | 形态 |
|---|---|
| 缓存 key | 目标 namespace 字符串（经 `KeyEncoders.canonical()` 编码） |
| 缓存 value | `StoredPolicy`（只含生效参数；**不含** `declaredStrategy`——那是代码里的事实） |
| 缓存 key 版本号 | **就是缓存体系既有的 key revision**（中央时钟时间戳，`max(now, cur+1)`）；只用于副本对账，**与运维乐观锁无关** |
| 配置版本号 | `configVersion`，由**数据源**生成并递增；运维 `change` 的乐观锁用它（见 §4.4.1） |
| 编码 | 由适配层提供（`RedisSerializer<StoredPolicy>`）；**core 依旧不碰字节** |

于是"自定义 HASH 布局 + `HINCRBY` 自增 + 批量版本脏检查"这套东西不需要了——
副本是否落后、何时回源，缓存层自己的 key revision 已经全包了。

### 2.4.3 为什么禁止 `NONE` / `DYNAMIC`

- **`NONE`**：策略读取会直连中央，每次读策略都打一次 Redis，而策略读取在启动期与热更检查时很密集；
  更重要的是"仓库没有任何本地副本"意味着中央抖动时仓库不可用，会连带业务无法装配；
- **`DYNAMIC`**：热 key 准入判定需要自身参数（QPS、窗口、冷却），而它要读的正是"参数"——
  形成自指与先有鸡还是先有蛋的问题；且接入判定会让"策略读取"的稳定性依赖自身状态，
  一旦判定逻辑出错，仓库先崩。
- **`STATIC` 是唯一自洽的选择**：行为确定、有本地副本、无自指。

启动期校验：仓库自身 namespace 的策略被配成 `NONE` 或 `DYNAMIC` ⇒ **启动失败**，错误信息说明原因。

### 2.4.4 变更传播（取代广播）

```text
节点 A： change → 校验 → 乐观锁(revision) → 中央 CAS 写 → 写穿本地副本 → 审计
节点 B： 下一次读策略时，自身的版本对账（5s 窗口）发现 revision 变化 → 清掉本地旧副本
        → 本次读回源中央 → 拿到新策略 → 后续装配使用新值
```

- **最大传播延迟 = `version.checkInterval` = 5s**（这就是"不需要广播"的代价，已明确接受）；
- `find` 命中本地副本时不会触发任何额外往返；
- 中央不可用：`failOpen=true` ⇒ 读本地副本继续提供服务；本地也没有 ⇒ `find` 返回 empty
  （业务用声明 + 内建默认装配，见 §6）。

### 2.4.5 读己之写

`change` 在同一进程内必须立刻可见：中央写成功后**写穿本地副本**（`put` 带中央返回的新 revision），
而不是等对账。这条是 §5.1「读己之写」的实现方式。

## 3. 解析（resolution）：三层优先级

```text
生效值 = 仓库里的 NamespacePolicy（若有）
       ?? 声明（strategy + description）+ 内建默认（其余参数）
       ?? 内建默认（连声明都没有时不会被触发——没有声明就没有 namespace）
```

要点：

1. **声明提供两个字段**：`strategy`、`description`；其余参数一律内建默认；
2. 仓库只要**存在**该 namespace 的记录，就整条生效（不做字段级合并）——
   避免"仓库改了 TTL、声明又偷偷把策略改回去"这类不可推理的混合态；
3. 内建默认只有**一套**（表见 §2.1–§2.3），不提供档位；
4. 解析结果必须**冻结**为不可变对象，`find` 返回的是同一份快照（同一 `configVersion` 内 identity 稳定，
   便于上层做 `==` 快速判断"换没换过"）。

## 4. 两层结构：仓库门面 + 仓库数据源

```
        ┌───────────────────────────── 业务装配 ─────────────────────────────┐
        ▼                                                                    │
 CacheStrategyRepository（门面，库提供，CachedCacheStrategyRepository）        │
        │                                                                    │
        ├─ 本地缓存：STATIC（30m，maxSize 默认 1000，可配，重启生效）  ← 本套缓存体系
        ├─ 中央缓存：TTL 3d（非空）/ 5m（空值），版本窗口 5s          ← 本套缓存体系
        └─ 回源 / 写穿↓
              CacheStrategyDataSource（数据源 SPI，开发者实现，纯 KV）
                    load(namespace) / loadAll() / save(policy, expectedConfigVersion)
```

**分工必须严格切开**：

| 层 | 感知什么 | 不感知什么 |
|---|---|---|
| **门面** | 版本号、TTL、两层缓存、对账、5s 传播、乐观锁判定、解析与校验 | 数据源用什么介质、怎么连 |
| **数据源** | 一条记录的读写：`load` / `loadAll` / `save` | 缓存、版本窗口、两层结构、TTL、对账 |

**为什么这样切**：开发者接自己的存储（DB、配置中心、甚至一个 Redis 的 hash）时，只需实现三个方法，
不需要理解缓存库的任何机制；而"策略读取要快、要能降级、要多节点 5s 内一致"这些缓存问题全部由门面兜住。

### 4.1 数据源 SPI

```java
/** 策略的权威持久值。开发者实现这个接口即可接入任意存储。 */
public interface CacheStrategyDataSource {

    /** @return 该 namespace 的记录；不存在返回 empty（不是错误） */
    Optional<StoredPolicy> load(String namespace);

    /** @return 全部记录；启动时枚举与运维视图用。返回空列表表示"一条都没有" */
    List<StoredPolicy> loadAll();

    /**
     * 写入并返回新的**配置版本号**（必须单调递增）。
     *
     * <p><strong>乐观锁是尽力而为</strong>：能 CAS 的实现（Redis Lua、DB 条件更新）
     * 应在 {@code expectedConfigVersion} 不匹配时返回 empty；实在不支持的实现可以退化为
     * last-write-wins 并照常返回新 revision——此时门面会在 {@link StrategyView} 里标注
     * {@code casSupported=false}，运维知道"并发覆盖不会报冲突"。</p>
     */
    OptionalLong save(StoredPolicy policy, long expectedConfigVersion);
}
```

约定：

1. **revision 由数据源生成**（时间戳或自增），必须单调；门面只比较、不自增；
2. `load` 未命中返回 `empty`，**不得**抛异常表示"没有"；
3. 数据源**不得**做缓存（不要在实现里再加一层 LRU），缓存是门面的事；
4. 数据源**不得**依赖 `cache4j` 的任何缓存实现（只依赖 `cache4j-core` 的模型）；
5. 单节点部署可以**不提供**数据源：门面退化为"声明 + 内建默认 + 进程内 `AtomicLong` 版本"。

### 4.2 读路径（门面）

```text
find(ns)
  ├─ 本地副本命中 → 直接返回（顺带 version.checkInterval 到点则对账，最多 5s 一次）
  ├─ 本地未命中 → 中央缓存 → 命中则回填本地
  └─ 中央也未命中 → dataSource.load(ns) → 有值则回填两层 → 无值则走声明 + 内建默认
```

- 中央缓存未命中且数据源也没有 ⇒ **不缓存"策略不存在"**给数据源，但**中央空值 TTL 5 分钟**会缓存
  这个"不存在"结论，避免每次启动都穿透到数据源；
- 数据源抛异常 ⇒ `failOpen=true` 时按"未命中"继续（用声明 + 默认），累加 `repoErrorCount`。

### 4.3 写路径（门面）

```text
change(req)
  → 校验 → 与当前比对 → 从当前 policy 取 configVersion 作为 expectedConfigVersion
  → dataSource.save(policy, expectedConfigVersion)     // 数据源负责持久化与版本递增
  → 失败/冲突 ⇒ REJECTED / CONFLICT（本机不变更）
  → 成功 ⇒ 用返回的新 revision 写穿两层缓存             // 读己之写
  → 审计 + 指标
```

- **写穿的顺序**：先写中央缓存（带新 revision），再写本地副本；两者都用 `putIfNewer`，
  避免与并发的回填/对账打架（本地持有更新版本时不会被旧值覆盖）；
- 其他节点不接收任何通知，靠 5s 版本对账发现（§2.4.4）。

### 4.4 数据源实现难度分级

| 级别 | 实现方式 | 乐观锁 | 门面行为 |
|---|---|---|---|
| **A（推荐）** | Redis Lua / DB 条件更新（`UPDATE … WHERE revision = ?`） | 真 CAS | 冲突返回 `CONFLICT` |
| **B（够用）** | 普通 KV `set` / DB upsert | last-write-wins | 永不冲突，`StrategyView.casSupported=false`，运维自行避免并发改同一 namespace |
| **C（单节点）** | 不提供数据源 | 进程内 `AtomicLong` | 重启回到声明值 |

### 4.4.1 两个版本号，是两个完全不同的东西

| | **缓存 key 版本号**（`revision`） | **缓存策略配置版本号**（`configVersion`） |
|---|---|---|
| 一句话 | 这个**缓存条目**的新旧 | 这套**策略配置**的新旧 |
| 归属层 | 缓存体系（本地副本 + `__rev:cache4j.strategy`） | 数据源记录（`StoredPolicy`） |
| 谁生成 | 缓存层 Lua：`max(now, cur+1)` | 数据源 `save` 时递增 |
| 类型/语义 | 中央时钟时间戳，**只比相等/不等** | 单调递增序号（时间戳或自增），**可比大小** |
| 用途 | 判断本地副本是否落后 → 触发 5s 对账 → 决定是否回源 | **运维乐观锁**（`expectedConfigVersion`）、审计、`StrategyView.configVersion` |
| 谁看得见 | 只有缓存层内部（日志/指标） | 运维（视图、审计、变更请求） |
| 数值可比性 | **跨节点不可比**（各自时钟打的时间戳） | **跨节点可比**（同一份数据源记录上的序号） |

关系与传播：

```text
节点 A： save(dataSource)                      → configVersion 7 → 8
       写穿中央缓存（新 policy + 缓存层自增的 revision）
节点 B： 对账发现「缓存 revision」变了          → 本地副本作废
       回源中央缓存（命中，不碰数据源）        → 拿到 configVersion=8 的 policy
       → configVersion 变了 ⇒ 触发运行时切换
数据源： 只在中央缓存未命中/过期（3 天）时被访问
```

**两条禁令**：

1. **不得把缓存 key revision 当 `expectedConfigVersion`**：它是缓存层内部时间戳，
   跨节点数值不可比，运维从视图里也读不到它——用它做乐观锁等于没有锁；
2. **不得把 `configVersion` 用于副本对账**：配置版本前进不代表"这个节点读过的副本"该作废，
   副本失效是缓存层的事（例如中央缓存条目被其他节点写穿后，靠 revision 判断落后）。

> 一句话记法：**`revision` 管"副本旧不旧"，`configVersion` 管"配置新不新"。**

### 4.5 `StoredPolicy` 与 `NamespacePolicy`

前者是**持久化形态**（字段可缺省、带 schema 版本），后者是**解析后的完整生效值**。二者不得互相替代：

- 数据源里只写"与内建默认不同"的字段是允许的（省空间），解析时补齐默认；
- `StoredPolicy` 必须能表达"这条记录没写 description"，以便识别"运维没改过描述"。

### 4.6 仓库自身缓存的参数来自启动配置，改需重启

自举缓存的参数**不能**放在策略仓库里（否则自指：读策略要先读策略），只能来自启动配置：

| 配置项（`StrategySelfCacheOptions`） | 默认 | 备注 |
|---|---|---|
| `maxSize` | **1 000** | 最可能需调的项：namespace 上千时上调 |
| `localTtl` | 30m | 本地副本存活 |
| `centralTtl` | 3d | 中央副本存活 |
| `centralNullTtl` | 5m | "该 namespace 无策略"的缓存时长 |
| `versionCheckInterval` | 5s | 即"多节点生效最大延迟"，**不建议调大** |

**全部需要重启生效**（它们决定了仓库自身缓存的构造参数，热改等于重建仓库自己）。
启动日志必须打印实际生效值：

```
[策略仓库]-启动-自举缓存 namespace=cache4j.strategy，策略=STATIC，maxSize=1000，localTtl=PT30M，
         centralTtl=PT72H，centralNullTtl=PT5M，versionCheck=PT5S
```

## 5. 一致性与线程模型

### 5.1 保证

| 保证 | 含义 |
|---|---|
| **读己之写** | `change` 成功返回后，同进程内 `find` 必须立即返回新值 |
| **最终一致** | 其他节点在仓库自用缓存的 `version.checkInterval`（固定 5s）内看到新值 |
| **`configVersion` 单调** | 同一 namespace 的配置版本只增不减；不同 namespace 相互独立 |
| **无部分应用** | 任何失败路径下，生效值与 `configVersion` 都不前进 |
| **不可变快照** | 解析结果不就地修改；变更 = 换引用 |

### 5.2 并发

- 同一 namespace 的 `change` 串行（内部锁或 CAS 重试），避免"两个运维同时改"产生中间态；
- 跨节点的并发由中央 CAS 裁决（revision 不符即失败），不做分布式锁；
- 读路径（`find`）必须**无锁**：缓存层自身的本地副本读取不加锁，解析结果不可变。

### 5.3 无后台线程、无广播

仓库自身**不得**创建线程、定时器、线程池，也**不做变更广播**（不订阅 pub/sub、不发事件）。
传播完全由仓库自用缓存的版本对账承担（§2.4.4）：

- 读策略时若本地副本已过期或版本落后，缓存层自动回源中央取新值；
- 最大传播延迟 = 仓库自身缓存的 `version.checkInterval` = **5s**（固定，不可配成更大值以免失去意义）；
- 需要"立即生效"的场景（本机运维改完马上要看到）由 §2.4.5 的写穿保证，不依赖传播。

### 5.4 一致性保证一览

| 场景 | 保证 |
|---|---|
| 本机 `change` 后立刻 `find` | **立即**看到新值（写穿本地副本） |
| 其他节点读取 | **≤ 5s** 看到新值（版本对账窗口） |
| 中央不可用 | 读本地副本继续服务；本地也没有 ⇒ `find` 返回 empty，业务用声明 + 默认装配 |
| 中央恢复 | 下一次读自然回源，无需补偿动作 |

## 6. 失败语义

| 场景 | 必须的行为 |
|---|---|
| **无 Store** | 单节点模式；启动日志明确标注；不报错 |
| 中央不可达（启动期） | **必须**用"声明 + 内建默认"装配成功，并打 `warn`（不得启动失败，除非 `failClosed` 配置为 true） |
| 中央不可达（运行期读策略） | 读本地副本继续服务；**不得**回落到声明值、不得清空已生效配置 |
| 数据源不可达（回源失败） | `failOpen=true` ⇒ 按未命中继续（声明 + 内建默认），`repoErrorCount++` + `warnThrottled`；**不得**缓存该失败 |
| 数据源不可达（写入失败） | `change` 返回 `REJECTED`，两层缓存与生效值都不变 |
| 存储记录损坏（字段解析失败） | 同"不可达"，且**不得**覆盖存储（不做自动修复） |
| 变更持久化失败 | `change` 返回 `REJECTED`，生效值不变（先持久化后生效，见 §9.4） |
| 目标配置校验失败 | `change` 返回 `REJECTED` + 具体字段与原因，生效值不变 |
| 运行时装配失败（如 `DYNAMIC+GLOBAL` 缺 coordinator） | `change` 返回 `REJECTED`；若发生在**读到他人提交的配置**时（本机装不出来）⇒ 保持旧值 + `error` 日志 + 指标，**不得**降级策略 |

**禁止**：任何失败路径下"猜一个策略继续跑"。要么明确用旧值，要么明确失败。

## 7. 部署模式

| 模式 | Store | 生效值来源 | 重启后 | 多节点一致 | 适用 |
|---|---|---|---|---|---|
| **单节点** | 无（无中央） | 声明 + 内建默认 | 回到声明值 | 不适用 | 开发/测试/单实例 |
| **多节点** | Redis（作为仓库自用缓存的中央层） | 中央 → 声明 → 默认 | **保持运维改过的值** | 是（≤5s） | 生产 |

启动日志必须标注模式：

```
[策略仓库]-启动-模式=多节点，store=Redis，namespace 数=3
[策略仓库]-启动-模式=单节点（无 store），namespace 数=3，重启将回到声明值
```

## 8. 仓库错误码映射

| 场景 | 错误码 | 说明 |
|---|---|---|
| 声明冲突 / 校验失败 / 解析失败 | `CACHE-00001 INVALID_SPEC` | 现有，复用 |
| 存储不可用 | `CACHE-00005 CENTRAL_UNAVAILABLE` | 现有，复用 |
| 存储超时 | `CACHE-00006 CENTRAL_TIMEOUT` | 现有，复用 |
| 乐观锁冲突 | 建议新增 `STRATEGY_CONFLICT` | 契约要求可区分"冲突"与"配置非法" |
| 变更被拒（校验/装配失败） | 建议新增 `STRATEGY_REJECTED` | 同上 |
| 策略视图读取失败 | 建议新增 `STRATEGY_UNAVAILABLE` | 运维端点用 |

> 新增码在实现阶段追加到 `CacheResCode`，本契约只要求"冲突"与"非法"**必须可区分**。

## 9. 仓库契约的不变量清单

1. `find` / `all` 无副作用；
2. 同一 `configVersion` 内 `find` 返回同一实例（identity 稳定）；
3. 解析不做字段级合并（整条生效）；
4. 变更 = 换引用，不就地改对象；
5. 同一 namespace 的变更串行，`configVersion` 单调；
6. 任何失败不改生效值、不推进 `configVersion`；
7. 读策略失败（中央不可达且本地无副本）不回落到声明值，而是 `find` 返回 empty 由装配层兜底；
8. 仓库不创建线程；
9. 无 Store 时仍能装配（单节点模式）；
10. 仓库不持有业务缓存实例，只持有配置（它自己那个 `cache4j.strategy` 缓存除外，那是自举设施）；
11. **`declare` 不得覆盖已存在的配置**：仓库里已有该 namespace ⇒ 跳过，不登记、不改动；
12. **仓库自身策略禁止 `NONE`/`DYNAMIC`**，启动期校验。

---

# 第二部分：仓库管理契约（Admin Contract）

## 10. 管理 SPI

```java
/** 运维管理入口。库只提供动作，不暴露 HTTP 端点、不做鉴权。 */
public interface CacheStrategyAdmin {

    /** @return 全部 namespace 的视图（含声明值、生效值、配置版本、来源、审计元数据） */
    List<StrategyView> list();

    /** @return 单个 namespace 的视图 */
    Optional<StrategyView> find(String namespace);

    /** 提交变更。幂等键 + 乐观锁 + 原因，见 §11–§13。 */
    ChangeResult change(StrategyChange change);
}
```

```java
/** 一次变更请求。字段不可变。 */
public record StrategyChange(
        String requestId,          // 幂等键，必填，全局唯一（建议 UUID 或 业务流水号）
        String namespace,          // 必填
        Map<String, String> patch, // 必填，只允许 §15 表中「可热更」的键；未出现的键保持不变
        long expectedConfigVersion,        // 必填：运维视角的**配置版本号**乐观锁；不知道当前值必须先 find()
        String operator,           // 必填，审计；鉴权在调用方完成
        String reason,             // 必填，审计
        long requestedAtMillis     // 必填，服务端可校验"过期请求"（如超过 5 分钟拒绝）
) { }

/** 变更结果。三态 + 明确的下一步。 */
public record ChangeResult(
        Outcome outcome,           // APPLIED / NO_CHANGE / CONFLICT / REJECTED
        long configVersion,        // 变更后的配置版本（CONFLICT 时为**当前**版本）
        String namespace,
        StrategyView current,       // 变更后（或当前）视图，便于调用方直接渲染
        String message,             // 人话；REJECTED 时必须含具体字段与原因
        String requestId
) { }
```

```java
/** 运维视图：把"声明值 / 生效值 / 谁改的"摊平给人看。 */
public record StrategyView(
        String namespace,
        String description,            // 生效值（运维可能改写）
        String declaredDescription,    // 声明值（可能已与生效值不同）
        LocalStrategy activeStrategy,
        LocalStrategy declaredStrategy,
        long maxSize,
        Duration localTtl,
        Duration localNullTtl,          // 派生值 = min(localTtl, centralNullTtl / N)，只读展示
        int localNullTtlDivisor,
        VersionSpec version,
        DynamicSpec dynamic,
        Duration centralTtl,
        Duration centralNullTtl,
        boolean failOpen,
        Source source,                  // DECLARED / REPOSITORY / DEFAULT
        long configVersion,
        long updatedAtMillis,
        String updatedBy,
        String reason,
        boolean consistentWithDeclared // 与声明值是否一致（false 时启动/视图都要提示）
) { }
```

## 11. 变更类型与允许范围

| 参数 | 允许 | 生效时机 | 副作用 |
|---|---|---|---|
| `strategy` | ✅ | 立即（重建 Runtime） | 见迁移矩阵（`STRATEGY.md` §6.4）；`STATIC→DYNAMIC` 清空本地副本 |
| `maxSize` / `localTtl` / `version` | ✅ | 立即 | 重建 Runtime；分区索引重建 |
| `dynamic.*`（含 `hotThresholdQps`、`window`、`cooldown`…） | ✅ | 立即 | 重建 Runtime；热点/候选集合重置 |
| `centralNullTtl` / `failOpen` | ✅ | 立即 | 只影响后续写入；空值 TTL 同时改变本地负副本上限 |
| `localNullTtlDivisor` | ✅ | 立即 | 同上（派生值变化） |
| `description` | ✅ | 立即 | 仅视图与日志 |
| `centralTtl` | ⚠️ | 立即 | **只影响后续写入**；已写入 key 保留旧 TTL，不做 retro 修正 |
| 本地空值 TTL 绝对值 | ❌ | — | 只能通过 `localNullTtlDivisor` 间接调 |
| 档位 | ❌ | — | 本设计无档位 |
| `namespace` | ❌ | — | 改名等于换缓存 |
| 中央连接 / 序列化 / key 编码 | ❌ | — | 基础设施变更，必须重新装配 |

`patch` 中出现未列出的键 ⇒ `REJECTED`（**不忽略未知键**，避免拼写错误被静默吞掉）。

## 12. 变更流程（状态机）

```text
                     ┌──────────────────────────────┐
  change(req) ──────►│ 1. 幂等检查（requestId）      │ 已处理 → 返回原结果
                     ├──────────────────────────────┤
                     │ 2. 参数解析与校验             │ 非法 → REJECTED（含字段+原因）
                     ├──────────────────────────────┤
                     │ 3. 与当前逐字段比较           │ 全等 → NO_CHANGE（不重建、不写存储）
                     ├──────────────────────────────┤
                     │ 4. 乐观锁检查（expectedConfigVersion）│ 不匹配 → CONFLICT（返回当前 configVersion）
                     ├──────────────────────────────┤
                     │ 5. 试装配（构建新 Runtime）   │ 失败 → REJECTED（生效值不变）
                     ├──────────────────────────────┤
                     │ 6. 持久化（DataSource.save）  │ 失败/冲突 → REJECTED / CONFLICT
                     ├──────────────────────────────┤
                     │ 7. 原子提交（换引用）         │ 唯一提交点
                     ├──────────────────────────────┤
                     │ 8. 审计 + 指标（无广播）      │ 其他节点靠 5s 版本对账自行发现
                     └──────────────────────────────┘
```

顺序的硬要求：

- **先持久化（6）后生效（7）**：数据源失败时本机不生效，避免"单节点自己变了、其他节点没变"；
- **第 5 步试装配**：先造出新 Runtime 再提交，确保"提交后不可能因装配失败而空转"；
- **第 8 步没有广播**：变更写在中央条目里，其他节点在下一次读策略时由版本对账发现（≤5s）；
  审计/指标写失败只影响可观测性，不回滚已生效的值。

## 13. 幂等、乐观锁与并发

| 机制 | 契约 |
|---|---|
| **幂等键** | `requestId` 必填；同一 `requestId` 重复提交返回**首次结果**（不重复生效）；保留窗口由实现决定（建议 ≥ 10 分钟） |
| **乐观锁** | `expectedConfigVersion` 必填且必须等于当前 `configVersion`；不匹配返回 `CONFLICT` + 当前值，调用方重新 `find` 后再试 |
| **并发同 key** | 同一 namespace 的 `change` 串行；不同 namespace 并行 |
| **NO_CHANGE** | 目标配置与当前逐字段相等 ⇒ 不写数据源、不换 Runtime、`configVersion` 不变 |
| **`configVersion` 生成** | 只能由**数据源**（`save` 时递增）或进程内 `AtomicLong`（无数据源的单节点）生成；**门面与调用方都不得自增** |
| **过期请求** | 建议服务端拒绝 `requestedAtMillis` 超过阈值（如 5 分钟）的请求，避免陈旧意图覆盖新配置 |

**不提供 `reset`**：没有"回到声明值"的动作。要回退就再 `change` 一次（把值写回去），
审计里能看到完整链条。`StrategyView.declaredStrategy` 只用于展示与告警，不是还原目标。

## 14. 校验规则（逐条，顺序固定）

1. `requestId` / `namespace` / `operator` / `reason` **非空**（空白拒绝）；
2. `patch` 键必须在 §11 允许表内；
3. 值可解析（数字、时长带单位、布尔、枚举名）；
4. 单字段约束：见 §2.1–§2.3（`maxSize > 0`、`jitter < checkInterval`、`reconcilePartitions` 为 2 的幂…）；
5. 跨字段：
   - `coordination=GLOBAL` ⇒ 必须提供 `global`，且 `cooldown >= demoteWindows × window`；
   - `centralNullTtl > centralTtl` ⇒ **拒绝**（负副本不该比真值活得久，这是管理动作，可以强校验）；
   - `localNullTtlDivisor < 1` ⇒ 拒绝；`centralNullTtl = ZERO` 时该值被忽略（允许仍填）；
6. 装配可行性：`DYNAMIC + GLOBAL` 且本节点没有 `HotKeyCoordinator` ⇒ 拒绝并说明"本节点缺少协调器，
   请改用 LOCAL 或补齐中央协调实现"。

> 声明侧的校验（`description` 非空等）在启动期完成，不在这里重复；但 `description` 被 patch 成空白
> 同样要拒绝。

## 15. 审计契约

每次**被接受**的变更（`APPLIED`）与**每次拒绝**（`REJECTED` / `CONFLICT`）都必须落审计：

| 字段 | 来源 |
|---|---|
| `requestId` | 请求 |
| `namespace` | 请求 |
| `operator` | 请求 |
| `reason` | 请求 |
| `requestedAtMillis` / `appliedAtMillis` | 请求 / 服务端 |
| `fromConfigVersion` → `toConfigVersion` | 提交前后 |
| `diff` | 逐字段 diff（只记变化的键，值做脱敏？——参数无敏感数据，直接记） |
| `outcome` + `message` | 结果 |

**日志格式**（沿用全项目约定 `[主题]-子过程-具体信息`）：

```
[策略仓库]-变更-user:detail-APPLIED，configVersion 7→8，strategy STATIC→DYNAMIC，maxSize 1000→5000，操作人=ops:zhangsan，原因=大促热点，requestId=9f2c…
[策略仓库]-变更-user:detail-REJECTED，DYNAMIC+coordination=GLOBAL 缺少 HotKeyCoordinator，configVersion 仍为 8，操作人=ops:zhangsan
[策略仓库]-变更-user:detail-CONFLICT，期望 configVersion=7，当前=8，操作人=ops:lisi
```

审计日志级别：`APPLIED` 用 `info`，其余用 `warn`。**不写业务 key 与值**（策略参数不包含业务数据）。

## 16. 权限与安全边界

- **库不做鉴权**：`operator` 只是审计字段，**必须**由调用方（HTTP 层/网关）验证身份后才可填写；
- 管理端点必须与业务端点隔离（不同 port / 不同 path / 内网）；
- `patch` 不接受任意表达式（不支持 SpEL、不支持脚本），只接受字面量；
- 建议入口加限流与开关：`cache4j.strategy.admin.enabled=false` 时 `change` 一律 `REJECTED`，
  防止误接入生产。

## 17. 观测

新增指标（归"控制层"，不参与 `hitRate`）：

```java
long strategyConfigVersion();              // 当前生效的**配置版本号**（gauge；不是缓存 key 的 revision）
long strategySwitchCount();        // 成功切换次数（读到新配置并完成 Runtime 替换）
long strategySwitchRejected();     // 装配被拒次数（本机装不出他人提交的配置）
long strategyChangeConflict();     // 乐观锁冲突次数
long strategyRepoReadCount();      // 读策略次数（来自仓库自用缓存）
long strategyRepoErrorCount();     // 读策略失败次数
long strategyRepoHitRate();        // 仓库自用缓存的本地命中率（诊断：太低说明窗口太大或策略读得太散）

> 没有"变更检查次数"这类指标了——传播由版本对账完成，相关观测看仓库自用缓存自身的
> `versionSyncCount` / `versionInvalidationCount`。
```

运维视图（`StrategyView`）必须能直接回答三个问题：

1. 这个 namespace 现在是什么策略、什么参数？（生效值）
2. 代码里声明的是什么？与生效值是否一致？（`declaredXxx` + `consistentWithDeclared`）
3. 谁在什么时候因为什么改的？（`updatedBy` / `updatedAtMillis` / `reason` / `configVersion`）

## 18. 测试矩阵

| 类别 | 用例 |
|---|---|
| 解析优先级 | 有仓库/无仓库/仓库缺该 namespace；内建默认逐字段验证 |
| 无 Store 模式 | 装配成功、日志标注、重启回到声明值 |
| 中央不可达 | 启动期降级成功；运行期读本地副本继续服务、计数 + 告警 |
| 幂等 | 同一 `requestId` 重复提交只生效一次、返回同一结果 |
| 乐观锁 | 两个 `change` 同 `expectedConfigVersion` ⇒ 一个 APPLIED 一个 CONFLICT |
| NO_CHANGE | 逐字段相同 ⇒ 不写数据源、不换 Runtime（identity 断言）、`configVersion` 不变 |
| 试装配失败 | 缺 coordinator ⇒ REJECTED，生效值与 `configVersion` 不变 |
| 持久化失败 | Store 抛异常 ⇒ REJECTED，本机不生效 |
| 未知 patch 键 | 拼写错误 ⇒ REJECTED（不静默忽略） |
| 派生值 | 改 `centralNullTtl` 或 `localNullTtlDivisor` ⇒ `localNullTtl` 按 `min(localTtl, c/N)` 变化 |
| 传播 | 节点 A 改、节点 B 在 5s（自用缓存 `checkInterval`）内生效；B 无流量时不生效（对账访问驱动语义） |
| 审计 | APPLIED/REJECTED/CONFLICT 各一条日志，字段齐全，不出现业务 key/值 |
| 边界 | `namespace` 改名、`strategy` 非法值、`patch` 空、`reason` 空、过期 `requestedAtMillis` |
| 登记不覆盖 | 仓库里已有 `user:detail` ⇒ 声明被跳过：配置不变、不报错、无重复登记（identity 断言） |
| 声明间冲突 | 两个枚举声明同一且仓库里**没有**的 namespace ⇒ 启动失败，报双方来源 |
| 仓库自举策略 | 自身 namespace 被配成 `NONE` 或 `DYNAMIC` ⇒ 启动失败，错误信息说明自指/不可用原因 |
| 自举传播 | 节点 A 改策略后本机立即生效；节点 B 在 5s 内读到新值；节点 B 无流量时不生效（对账访问驱动） |
| 数据源未命中 | 本地/中央/数据源都没有 ⇒ 用声明 + 内建默认装配；"不存在"结论被中央空值 TTL 缓存 5m |
| 数据源异常 | `load` 抛异常 ⇒ 按未命中继续 + `repoErrorCount++`；**不得**缓存失败结果 |
| 数据源写入失败 | `save` 抛异常/返回 empty ⇒ `REJECTED`/`CONFLICT`，两层缓存不变 |
| 数据源无 CAS（B 级） | `save` 忽略 `expectedConfigVersion` ⇒ 永不 `CONFLICT`，`StrategyView.casSupported=false` |
| 写穿顺序 | `change` 成功后本机 `find` 立即新值；并发对账不会把旧值回填回来（`putIfNewer` 保护） |
| 自举参数 | `maxSize` 默认 1000；改配置需重启；启动日志打印实际生效值 |

## 19. 模块归属与分期

| 模块 | 内容 |
|---|---|
| `cache4j-core` | 全部 SPI 与模型：`CacheStrategyRepository`（门面接口）、**`CacheStrategyDataSource`（数据源 SPI）**、`CacheStrategyAdmin`、`NamespacePolicy`、`StoredPolicy`、`StrategyChange`、`ChangeResult`、`StrategyView`、`StrategySelfCacheOptions`；`InMemoryCacheStrategyRepository`（单节点，无数据源、无中央） |
| **`cache4j-strategy`（新增）** | `CachedCacheStrategyRepository`（门面实现）：用本库的 `TwoLevelCache`（namespace=`cache4j.strategy`，`STATIC`）承载策略读取与写穿；把 `CacheStrategyDataSource` 当作回源；解析三层优先级；自举校验（禁止 NONE/DYNAMIC）。依赖 `core` + `local` + `central`，**不依赖** `spring*` |
| `cache4j-spring-redis` | ① `RedisValueStore.writeIfRevision(...)`（乐观锁 Lua，供门面写穿用）；② **`RedisCacheStrategyDataSource`**（数据源实现：直接用 RedisTemplate 读写 `StoredPolicy`，不经缓存体系，Lua 做 CAS）；③ `RedisSerializer<StoredPolicy>` 默认实现 |
| `cache4j-spring` | 端点（可选 Actuator）、`@CacheNamespace` 扫描接入 |
| `cache4j-starter` | 默认装配与开关配置（含"仓库自身策略被配成 NONE/DYNAMIC 即启动失败"的校验） |

| 期 | 内容 |
|---|---|
| **P1** | 仓库门面 SPI + `CacheStrategyDataSource` SPI + `InMemoryCacheStrategyRepository`（无数据源/无中央）+ 解析优先级 + 启动装配 |
| **P2** | 管理 SPI + `change` 状态机 + 校验 + 审计 + 指标（单节点热更） |
| **P3** | `CachedCacheStrategyRepository`（自举缓存）+ `RedisCacheStrategyDataSource` + 5s 版本对账传播（多节点一致） |
| **P4** | 端点、`@Scheduled` 触发、`cache4j-starter` 装配 |

## 20. Redis 布局契约（两套，别混）

分层之后 Redis 里有两份数据，前缀不同、职责不同：

### 20.1 门面的中央缓存层（副本，可丢）

```text
# 数据：普通缓存条目，value 是 StoredPolicy 的编码字节
<cache4j.strategy 前缀>:<encoded(namespace)>  →  StoredPolicy

# 版本表：缓存体系自带
__rev:cache4j.strategy
    <encoded(namespace)>  →  <revision>|<expireAtMillis>
```

- TTL：**3 天**（非空）/ **5 分钟**（空值，即"该 namespace 没有策略"的负缓存）；
- `revision` 是**缓存 key 版本号**（由既有 Lua 生成，`max(now, cur+1)`，"版本前进 + 值生效"原子完成），
  只用于副本对账；**它和运维看到的 `configVersion` 是两回事**（§4.4.1）；
- **可以丢**：丢了只是要回源数据源一次，不影响正确性。

### 20.2 数据源（权威值，不可丢）

`RedisCacheStrategyDataSource` 的布局（实现可自定，但建议如下）：

```text
cache4j.policy:<namespace>   HASH
    data      StoredPolicy 的编码字节
    configVersion <long>，单调递增（运维乐观锁用）
    updatedAt <millis>          # 排障用，不参与判定
```

写用 Lua（`cache4j.policy:cas`）：

```lua
-- KEYS[1]=policy key, ARGV[1]=expectedConfigVersion, ARGV[2]=newData, ARGV[3]=nowMillis
local cur = tonumber(redis.call('HGET', KEYS[1], 'configVersion') or '0')
if tonumber(ARGV[1]) >= 0 and cur ~= tonumber(ARGV[1]) then
  return nil                                   -- 版本冲突
end
local next = math.max(tonumber(ARGV[3]), cur + 1)
redis.call('HSET', KEYS[1], 'data', ARGV[2], 'configVersion', next, 'updatedAt', ARGV[3])
return next
```

硬性要求：

1. **两套前缀不得重叠**：`cache4j.strategy`（缓存）与 `cache4j.policy:`（数据源）互不相干；
   `clear(<业务 namespace>)` 两者都不得波及；
2. **数据源不做缓存**：它只回答"当前权威值是什么"，不要在其中再加 LRU/TTL；
3. **`configVersion` 单调**：`max(now, cur+1)` 或自增，二者都行，但必须在同一 namespace 上单调；
4. **`expectedConfigVersion = -1` 表示"无条件下写"**（首次写入/迁移时用），仅限运维脚本，
   门面自身**不得**用它绕过乐观锁；
5. **不做自动修复**：条目解析失败时不得回写，返回错误让门面按未命中处理。

## 21. 开放的契约问题

| # | 问题 | 备选 | 倾向 |
|---|---|---|---|
| R1 | `change` 的 `patch` 用扁平 `Map<String,String>` 还是强类型对象 | 扁平字符串 / 每种变更一个强类型 record | **扁平 + 白名单键**（运维脚本友好），但要求键名稳定并进契约 |
| R2 | 单节点模式下是否也要落本地文件（重启不丢） | 不落 / 落一个 properties | 不落（单节点就是开发/测试用），多节点用中央自用缓存 |
| R3 | `cache4j.strategy.admin.enabled` 默认值 | 默认开 / 默认关 | **默认关**（生产要显式开启管理端点），但需在 starter 里方便打开 |
| R4 | 审计是否也要持久化（如写数据源） | 只打日志 / 也存一份 | 只打日志（日志系统负责留存），数据源只存当前值 |
| R5 | `StrategyView.consistentWithDeclared` 不一致时是否告警 | 只展示 / 启动 + 每次检查告警 | 启动 `warn` 一次 + 视图展示，**不**每轮检查刷日志 |
| R6 | 数据源 `save` 的返回语义 | 成功返回新 `configVersion` / 冲突返回 empty | 返回 `OptionalLong`：`empty` = 版本冲突 → 门面直接映射为 `CONFLICT` |
| R7 | 是否强制数据源支持 CAS | 强制 / 尽力而为 | **尽力而为**（§4.4 分级 A/B/C），并让 `StrategyView.casSupported` 暴露能力，避免为了强一致把小团队挡在门外 |
| R8 | 是否提供开箱即用的数据源 | 只给 SPI / 给 Redis 与 JDBC 两个实现 | 先给 `RedisCacheStrategyDataSource`（spring-redis 模块），JDBC 等有需求再加 |
