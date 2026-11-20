# 缓存策略仓库契约 & 仓库管理契约

> 状态：**契约草案，未实现**。
> 上游：`STRATEGY.md`（声明与发现：枚举三字段、`META-INF/services` 与 Spring 扫描、转换链路、优先级、运行时切换机制）。
> 下游：`DESIGN.md` / `LOCAL.md` / `CENTRAL.md`（缓存运行时本身）。
> 本文只定"仓库里有什么、谁能改、怎么改、改完怎么保证一致"，不讨论缓存读写路径。

## 0. 定位

| 组件 | 职责 | 不负责 |
|---|---|---|
| **声明**（`CacheStrategyDecl`） | 开发者表达"要一个叫什么、初始什么策略、干什么用"的缓存 | 任何参数 |
| **仓库门面**（`CacheStrategyRepository`，§1–§7） | 持有**生效配置**，是"当前这个 namespace 到底怎么配"的唯一权威；**对接本套缓存体系**（本地 + 中央两层）。业务端只读，管理端才写 | 缓存实例的创建与读写（业务侧） |
| **仓库数据源**（`CacheStrategyDataSource`，§4） | 提供策略的**权威持久值**：`register`（初始注册，不覆盖）+ `update`（乐观锁更新）；**不对接**本套缓存体系 | 缓存、版本、一致性（由门面负责） |
| **管理服务**（开发者或运维团队独立部署，§7） | 分页查询、详情修改、鉴权、审计持久化、限流 | 库不提供，只提供 SPI 与日志 |
| **管理**（本文 §8–§15） | 给出变更入口、校验、审计、观测 | 鉴权（使用方的 HTTP 层负责） |

**核心不变量（贯穿全文）**

1. 仓库是生效值的**唯一权威**；声明值只作初始兜底与审计对照。
2. 生效值解析顺序：**仓库 → 声明 → 内建默认**；三层都缺才算错误。
3. **没有仓库也能跑**：不实现数据源、不启用仓库时，声明 + 内建默认即可装配，不报错。
   但**没有"降级形态的仓库"**——要么完整启用（共享数据源），要么完全不启用。
4. **不提供 reset**：要回退就再改一次，审计留痕优先于"一键还原"。
5. **无后台线程、无广播**：仓库自身不建线程也不推送变更；传播由仓库自用缓存的版本对账承担（§2.4.4，≤5s）；
6. **仓库自举**：仓库门面本身就是缓存体系里的 `cache4j.strategy` 命名空间，`STATIC` 策略，
   禁止 `NONE`/`DYNAMIC`（§2.4.3）；自身缓存参数来自**启动配置**，改需重启（§2.4.6）。
7. **两层分工**：门面对接缓存体系，**数据源不对接**；开发者只需实现数据源（§4）。
8. **业务端纯读**：业务 JVM 只 `find`/`all` + 消费变更；`change` 只在独立的管理服务里装配（§7）。
9. **不做半应用**：任何一步失败，生效值不变。

---

# 第一部分：仓库契约（Repository Contract）

## 1. SPI 全景

```java
/**
 * 仓库门面：进程内生效配置的唯一权威，内部对接本套缓存体系（§2.4）。
 *
 * <p>部署边界：<strong>业务端只调用 find / all</strong>；change 只在管理服务里装配（§7）。
 * 业务端不提供任何管理端点。</p>
 */
public interface CacheStrategyRepository {

    // ---------- 启动期（两端都做） ----------

    /**
     * 登记一条声明（来自枚举）。
     *
     * <p><strong>仓库已有该 namespace 的策略时，本方法必须直接跳过</strong>：不登记、不覆盖、
     * 不改动已有信息（仓库是权威，代码声明不得回写已存在的配置）。跳过是幂等行为，
     * 只记 debug 日志，不告警。</p>
     *
     * <p>仅当两个<strong>声明</strong>争抢同一个且仓库里还没有的 namespace 时，才抛冲突。</p>
     */
    void declare(CacheStrategyDecl decl, DeclSource source);

    /** 登记完毕，解析并冻结初始生效值。返回被解析出的 namespace 列表（保序）。 */
    List<String> bootstrap();

    // ---------- 读取（业务端唯一会用到的部分；幂等、无副作用） ----------

    /** @return 生效策略三态；见 {@link PolicyLookup} */
    PolicyLookup find(String namespace);

    /** @return 全部生效策略（保序：按 bootstrap 解析顺序） */
    List<NamespacePolicy> all();

    // ---------- 变更（**仅管理服务**装配） ----------

    /** 提交一次变更。见 §12。 */
    ChangeResult change(StrategyChange change);
}
```

**`PolicyLookup` 三态**（`Optional` 无法区分"确实没配"和"读不到"）：

```java
public sealed interface PolicyLookup {
    record Found(NamespacePolicy policy) implements PolicyLookup { }
    /** 数据源明确回答"没有这个 namespace" */
    record Absent() implements PolicyLookup { }
    /** 数据源不可达 / 记录损坏 —— 与 Absent 的处理完全不同（前者要告警，后者用默认） */
    record Unavailable(Throwable cause) implements PolicyLookup { }
}
```

**契约约束**

- `find` / `all` **必须无副作用**（不得因读而创建、修复或写回任何状态）；
- `declare` / `bootstrap` / `change` **必须线程安全**；同一 namespace 的变更串行生效；
- 所有方法**不得抛受检异常**；失败一律 `CacheException`；
- **没有批量版本接口 / `refresh` / 广播**：变更传播完全由仓库自用缓存的版本对账承担（§2.4）；
- 中央不可用时 `find` 走缓存的降级路径并返回 `Unavailable`，**不得**改动生效值（见 §6）。

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

### 2.4.5 写穿（只发生在管理服务）

**业务端是纯读者**，没有写穿这回事。管理服务执行 `change` 后需要立刻看到自己的变更：

- 数据源 `update` 成功后，把新 policy 写穿到自己的中央缓存与本地副本（`putIfNewer`，
  本地已有更新版本时不会被旧值覆盖）；
- 之后业务端靠 5s 版本对账发现，不需要通知。

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

    /** @return 全部记录；启动枚举与运维分页查询用。返回空列表表示"一条都没有" */
    List<StoredPolicy> loadAll();

    /**
     * 初始注册：写一条新记录。<strong>已存在则返回 false，绝不覆盖。</strong>
     *
     * <p>用于"把声明固化为持久记录"或管理端首次创建某 namespace 的策略。</p>
     *
     * @return true = 新建成功；false = 已存在（未改动任何数据）
     */
    boolean register(StoredPolicy policy);

    /**
     * 运维变更：乐观锁更新。
     *
     * <p>仅当存储里的 `configVersion` 等于 {@code expectedConfigVersion} 时写入并返回新版本号；
     * 否则返回 empty（冲突）。不支持 CAS 的实现可以退化为 last-write-wins 并照常返回新版本号，
     * 此时门面把 {@code StrategyView.casSupported} 置为 false。</p>
     */
    OptionalLong update(StoredPolicy policy, long expectedConfigVersion);
}
```

约定：

1. **`configVersion` 由数据源生成**（时间戳或自增），必须单调；门面只比较、不自增；
2. `load` 未命中返回 `empty`，**不得**抛异常表示"没有"；异常一律表示"不可用"（→ `Unavailable`）；
3. 数据源**不得**做缓存（不要在实现里再加一层 LRU），缓存是门面的事；
4. 数据源**不得**依赖 `cache4j` 的任何缓存实现（只依赖 `cache4j-core` 的模型）；
5. **`register` 与 `update` 语义不得混用**：注册不覆盖、更新必带版本。

### 4.2 读路径（门面）

```text
find(ns)
  ├─ 本地副本命中 → 直接返回 Found（到点则顺带做版本对账，最多 5s 一次）
  ├─ 本地未命中 → 中央缓存 → 命中则回填本地
  └─ 中央也未命中 → dataSource.load(ns)
        ├─ 有值 ⇒ 回填两层 → Found
        ├─ empty ⇒ "该 namespace 没有配置" ⇒ 中央缓存一个空值（5m）→ Absent
        └─ 异常 ⇒ Unavailable（不回填、不缓存失败）
```

### 4.3 写路径（管理服务）

```text
change(req)
  → 校验（整条 NamespacePolicy，见 §14）
  → 与当前逐字段比较：全等 ⇒ NO_CHANGE（不写、不换 Runtime）
  → 读当前 configVersion 作为 expectedConfigVersion
  → 数据源：
      该 namespace 此前无记录 ⇒ register(policy)   —— false（已被别人抢先建）⇒ CONFLICT
      已有记录              ⇒ update(policy, expectedConfigVersion) —— empty ⇒ CONFLICT
  → 失败 ⇒ REJECTED / CONFLICT（本机与数据源都不变）
  → 成功 ⇒ 写穿自己的中央缓存 + 本地副本（putIfNewer）→ 审计
```

- 先持久化、后写穿：数据源失败时管理服务不生效，避免"管理服务看到新值、业务端看不到"；
- 业务端不写穿，它只会在 5s 内通过对账发现新版本。

### 4.4 数据源能力分级（只有"有没有 CAS"一个维度）

| 级别 | 实现方式 | `update` 的乐观锁 | 门面行为 |
|---|---|---|---|
| **A** | Redis Lua / DB 条件更新（`UPDATE … WHERE configVersion = ?`） | 真 CAS | 冲突返回 `CONFLICT` |
| **B** | 普通 KV `set` / DB upsert | last-write-wins | 永不冲突，`StrategyView.casSupported=false` |

> **没有"无数据源"这一级**：要么完整启用仓库（必须有数据源），要么完全不启用（声明 + 内建默认）。
> 版本号必须来自共享存储，否则 N≥2 个业务端之间不可比。

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
| **管理端读己之写** | `change` 成功返回后，**管理服务进程内** `find` 立即返回新值（写穿）；业务端不适用 |
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
| 管理服务 `change` 后立刻 `find` | **立即**看到新值（写穿本地副本） |
| 其他节点读取 | **≤ 5s** 看到新值（版本对账窗口） |
| 中央不可用 | 读本地副本继续服务；本地也没有 ⇒ `find` 返回 empty，业务用声明 + 默认装配 |
| 中央恢复 | 下一次读自然回源，无需补偿动作 |

## 6. 失败语义

| 场景 | 必须的行为 |
|---|---|
| **未启用仓库**（不实现数据源） | 声明 + 内建默认装配，启动日志明确标注"策略仓库未启用"；不报错 |
| 中央不可达（启动期） | **必须**用"声明 + 内建默认"装配成功，并打 `warn`（不得启动失败，除非 `failClosed` 配置为 true） |
| 中央不可达（运行期读策略） | 读本地副本继续服务；**不得**回落到声明值、不得清空已生效配置 |
| 数据源不可达（回源失败） | `find` 返回 `Unavailable`；上层决定用声明 + 内建默认并 `repoErrorCount++` + `warnThrottled`；**不得**把失败缓存成 `Absent` |
| 数据源不可达（写入失败） | `change` 返回 `REJECTED`，两层缓存与生效值都不变 |
| 存储记录损坏（字段解析失败） | 同"不可达"，且**不得**覆盖存储（不做自动修复） |
| 变更持久化失败 | `change` 返回 `REJECTED`，生效值不变（先持久化后生效，见 §9.4） |
| 目标配置校验失败 | `change` 返回 `REJECTED` + 具体字段与原因，生效值不变 |
| 运行时装配失败（如 `DYNAMIC+GLOBAL` 缺 coordinator） | `change` 返回 `REJECTED`；若发生在**读到他人提交的配置**时（本机装不出来）⇒ 保持旧值 + `error` 日志 + 指标，**不得**降级策略 |

**禁止**：任何失败路径下"猜一个策略继续跑"。要么明确用旧值，要么明确失败。

## 7. 部署拓扑：业务端 / 管理端

**不存在"单节点特殊模式"**：一律按"N 个客户端连同一套共享存储（N ≥ 1）"设计。

| 角色 | 装配的东西 | 提供的能力 |
|---|---|---|
| **业务端**（每个业务 JVM） | `CacheStrategyRepository` 的**只读部分**：`declare` / `bootstrap` / `find` / `all` + 自用缓存 | 按策略装配缓存、5s 对账消费变更 |
| **管理服务**（独立部署，通常 1~2 实例） | 完整 `CacheStrategyRepository` + `CacheStrategyAdmin` + 鉴权 + 审计持久化 + 分页查询 | 查询、修改、审计、限流；**不跑业务缓存** |

**业务端严禁暴露管理端点**：管理动作只从管理服务进来，否则多实例各开一套端口必然乱套。

### 7.1 两种接法（管理端改了，业务端怎么看到）

| 接法 | 拓扑 | 前提 |
|---|---|---|
| **T1 共享存储** | 管理端与业务端**直连同一套数据源 + 同一套中央缓存** | 数据源与中央缓存对两端可见（同 Redis / 同 DB） |
| **T2 数据源经管理端 API** | 业务端只连**同一套中央缓存**；它们的 `CacheStrategyDataSource` 是**指向管理服务 API 的 HTTP 客户端** | 管理服务把数据源包装成 API；业务端不直连存储 |

两种接法对**中央缓存**的要求相同：**必须是同一套**，否则版本对账不成立（各看各的副本，5s 传播无从谈起）。

```text
T1: 业务端 ─┬─► 中央缓存（cache4j.strategy）─► 数据源（Redis/DB）
             └──────────────────────────────────▲
    管理端 ─────────────────────────────────────┘

T2: 业务端 ──► 中央缓存（cache4j.strategy）        ← 必须有
             └► 数据源 = HTTP → 管理服务 API ──► 真正数据源
```

失败语义（两端一致）：

- 中央缓存不可达 ⇒ 读本地副本；本地也没有 ⇒ `Unavailable`；
- 数据源不可达（T1 直连或 T2 的 API 挂掉）⇒ `Unavailable`，**不得**缓存成 `Absent`；
- 业务端**不会**因为数据源挂了而启动失败（除非显式配 `failClosed`）。

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
9. 未启用仓库时仍能装配（声明 + 内建默认）；启用则必须有数据源，没有"降级仓库"形态；
10. 仓库不持有业务缓存实例，只持有配置（它自己那个 `cache4j.strategy` 缓存除外，那是自举设施）；
11. **`declare` 不得覆盖已存在的配置**：仓库里已有该 namespace ⇒ 跳过，不登记、不改动；
12. **仓库自身策略禁止 `NONE`/`DYNAMIC`**，启动期校验。

---

# 第二部分：仓库管理契约（Admin Contract）

## 10. 管理 SPI（只在管理服务装配）

```java
/** 运维管理入口。库只提供动作，不暴露 HTTP 端点、不做鉴权。 */
public interface CacheStrategyAdmin {

    /** @return 分页视图（运维先列表再进详情，见 §11） */
    Page<StrategyView> page(String keyword, int page, int size);

    /** @return 单个 namespace 的完整视图（详情页） */
    Optional<StrategyView> find(String namespace);

    /** 提交变更：整条 NamespacePolicy + 版本号 + 原因。 */
    ChangeResult change(StrategyChange change);
}
```

```java
/** 一次变更请求。字段不可变。 */
public record StrategyChange(
        String requestId,          // 幂等键，必填（UUID 或业务流水号）
        String operator,           // 必填，审计；鉴权在调用方完成
        String reason,             // 必填，审计
        long requestedAtMillis,    // 必填，服务端可拒绝过期请求（如 > 5 分钟）
        NamespacePolicy policy     // 必填，**完整对象**（见下方说明）
) { }

/** 变更结果。四态 + 明确的下一步。 */
public record ChangeResult(
        Outcome outcome,           // APPLIED / NO_CHANGE / CONFLICT / REJECTED
        String namespace,
        long configVersion,        // 变更后的版本（CONFLICT 时为**当前**版本）
        StrategyView current,      // 变更后（或当前）视图，便于调用方直接渲染
        String message,            // 人话；REJECTED 时必须含具体字段与原因
        String requestId
) { }
```

**为什么传完整 `NamespacePolicy` 而不是 `patch`**：运维的实操路径是"列表/模糊分页 → 点进详情 →
改其中几项 → 提交"，此时**手里已经有完整对象**（详情页读出来的那个）。直接传完整对象：

- 强类型、IDE 有提示、没有字符串键拼写错误；
- 语义明确：提交的就是"改完之后应该长这样"，不存在"哪些字段没传所以要保留"的歧义；
- 想把某项恢复成内建默认，直接把默认值填进去即可（不需要"空字符串表示删除"这种约定）。

代价是调用方必须先读后写（`find` → 改 → `change`），这与乐观锁正好配套：读到的 `configVersion`
原样回传，期间被别人改过就会 `CONFLICT`。

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

由于传入的是完整 `NamespacePolicy`，上表的含义变成"**这些字段的值会被采纳**"：

- 表中 ❌ 的项（本地空值 TTL 绝对值、档位、`namespace`、中央连接）**不允许出现在 policy 里**
  （模型里本来就没有这些字段）；
- 表中 ⚠️ 的项（`centralTtl`）允许改，但只影响后续写入；
- 服务端会把提交的 policy 与当前值**逐字段比较**，全等即 `NO_CHANGE`。

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
                     │ 6. 持久化                     │ 无记录→register(否⇒CONFLICT)
                     │    （register 或 update）     │ 有记录→update(空⇒CONFLICT)
                     ├──────────────────────────────┤
                     │ 7. 原子提交（换引用）         │ 唯一提交点
                     ├──────────────────────────────┤
                     │ 8. 审计 + 指标（无广播）      │ 其他节点靠 5s 版本对账自行发现
                     └──────────────────────────────┘
```

顺序的硬要求：

- **先持久化（6）后生效（7）**：数据源失败时管理服务不生效，避免"管理端看到新值、业务端看不到"；
- **第 5 步试装配**：先造出新 Runtime 再提交，确保"提交后不可能因装配失败而空转"；
- **第 8 步没有广播**：变更写在中央条目里，其他节点在下一次读策略时由版本对账发现（≤5s）；
  审计/指标写失败只影响可观测性，不回滚已生效的值。

## 13. 幂等、乐观锁与并发

| 机制 | 契约 |
|---|---|
| **幂等键** | `requestId` 必填；同一 `requestId` 重复提交返回**首次结果**（不重复生效）。**作用域仅本管理服务进程内存**（保留 ≥ 10 分钟）；多实例管理服务之间不共享幂等表，重复请求由乐观锁兜底成 `CONFLICT` |
| **乐观锁** | 由数据源 `update(policy, expectedConfigVersion)` 裁决；不匹配返回 `CONFLICT` + 当前版本，调用方重新 `find` 后再试 |
| **注册 vs 更新** | 该 namespace 在数据源里**没有记录** ⇒ `register`（不覆盖）；**已有记录** ⇒ `update`（必带版本）。两者不得混用 |
| **并发同 key** | 同一 namespace 的 `change` 串行；不同 namespace 并行 |
| **NO_CHANGE** | 目标配置与当前逐字段相等 ⇒ 不写数据源、不换 Runtime、`configVersion` 不变 |
| **`configVersion` 生成** | 只能由**数据源**（`register`/`update` 时生成）递增；**门面与调用方都不得自增** |
| **过期请求** | 建议服务端拒绝 `requestedAtMillis` 超过阈值（如 5 分钟）的请求，避免陈旧意图覆盖新配置 |

**不提供 `reset`**：没有"回到声明值"的动作。要回退就再 `change` 一次（把值写回去），
审计里能看到完整链条。`StrategyView.declaredStrategy` 只用于展示与告警，不是还原目标。

## 14. 校验规则（逐条，顺序固定）

1. `requestId` / `operator` / `reason` **非空**（空白拒绝）；`policy.namespace` 非空且符合字符规则；
2. `operator` / `reason` **不含控制字符、长度 ≤ 128**（防日志注入，见 §15）；
3. 整条 `NamespacePolicy` 字段完整（不允许 null 表示"不变"，要保留就填原值）；
4. 单字段约束：见 §2.1–§2.3（`maxSize > 0`、`jitter < checkInterval`、`reconcilePartitions` 为 2 的幂…）；
5. 跨字段：
   - `coordination=GLOBAL` ⇒ 必须提供 `global`，且 `cooldown >= demoteWindows × window`；
   - `centralNullTtl > centralTtl` ⇒ **拒绝**（负副本不该比真值活得久，这是管理动作，可以强校验）；
   - `localNullTtlDivisor < 1` ⇒ 拒绝；`centralNullTtl = ZERO` 时该值被忽略（允许仍填）；
6. 装配可行性：`DYNAMIC + GLOBAL` 且本节点没有 `HotKeyCoordinator` ⇒ 拒绝并说明"本节点缺少协调器，
   请改用 LOCAL 或补齐中央协调实现"；
7. **`policy.namespace` 必须已存在**（在数据源或声明里）；不存在则拒绝——创建新 namespace 的策略
   属于"新增缓存"，应由代码声明后部署，而不是运维在线凭空造一个。

> 声明侧的校验（`description` 非空等）在启动期完成，不在这里重复；但提交的 `policy.description`
> 为空白同样要拒绝。

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

**库不负责审计持久化**（R4）：本库只输出结构化日志，由日志系统负责留存。
需要"可查询的变更历史 / 一键回看旧值"这种能力时，**由管理服务自己写审计表**——
它本来就有数据库、有鉴权、有事务。库不提供 `history` API。

## 16. 权限与安全边界

- **库不做鉴权**：`operator` 只是审计字段，**必须**由管理服务的 HTTP 层/网关验证身份后才可填写；
- **管理端点只存在于管理服务**（§7），业务 JVM 一律不暴露；
- 变更请求只接受**字面量值**（`NamespacePolicy` 的字段），不允许表达式/脚本；
- 限流属于管理服务的职责（库不在热路径上做限流）；库侧只要求"未装配 Admin 就没有变更入口"。

## 17. 观测

新增指标（归"控制层"，不参与 `hitRate`）：

```java
long strategyConfigVersion();              // 当前生效的**配置版本号**（gauge；不是缓存 key 的 revision）
long strategySwitchCount();        // 成功切换次数（读到新配置并完成 Runtime 替换）
long strategySwitchRejected();     // 装配被拒次数（本机装不出他人提交的配置）
long strategyChangeConflict();     // 乐观锁冲突次数
long strategyRepoReadCount();      // 读策略次数（来自仓库自用缓存）
long strategyRepoErrorCount();     // 读策略失败次数
long strategyRepoHitRate();        // 仓库自用缓存的本地命中率（诊断：太低说明策略读得太散）
long strategyAbsentCount();        // find 返回 Absent 的次数（策略确实没配）
long strategyUnavailableCount();   // find 返回 Unavailable 的次数（数据源/中央读不到）

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
| 整条对象提交 | 提交的 policy 缺字段（null）⇒ REJECTED；与当前逐字段相同 ⇒ NO_CHANGE |
| 注册 vs 更新 | 无记录 ⇒ `register` 成功；已有记录再 `register` ⇒ false 且数据不变；改已有 ⇒ 必须走 `update` |
| 派生值 | 改 `centralNullTtl` 或 `localNullTtlDivisor` ⇒ `localNullTtl` 按 `min(localTtl, c/N)` 变化 |
| 传播 | 节点 A 改、节点 B 在 5s（自用缓存 `checkInterval`）内生效；B 无流量时不生效（对账访问驱动语义） |
| 审计 | APPLIED/REJECTED/CONFLICT 各一条日志，字段齐全，不出现业务 key/值 |
| 边界 | `namespace` 改名（拒绝）、`strategy` 非法值、`policy` 字段 null、`reason` 空、
`operator` 含控制字符或超 128 字符、过期 `requestedAtMillis` |
| 登记不覆盖 | 仓库里已有 `user:detail` ⇒ 声明被跳过：配置不变、不报错、无重复登记（identity 断言） |
| 声明间冲突 | 两个枚举声明同一且仓库里**没有**的 namespace ⇒ 启动失败，报双方来源 |
| 仓库自举策略 | 自身 namespace 被配成 `NONE` 或 `DYNAMIC` ⇒ 启动失败，错误信息说明自指/不可用原因 |
| 自举传播 | 节点 A 改策略后本机立即生效；节点 B 在 5s 内读到新值；节点 B 无流量时不生效（对账访问驱动） |
| `find` 三态 | 数据源无记录 ⇒ `Absent`（并缓存空值 5m）；数据源异常 ⇒ `Unavailable`（**不**缓存失败）；有值 ⇒ `Found` |
| 数据源异常 | `load` 抛异常 ⇒ 按未命中继续 + `repoErrorCount++`；**不得**缓存失败结果 |
| 数据源写入失败 | `register`/`update` 抛异常或返回 empty ⇒ `REJECTED`/`CONFLICT`，两层缓存不变 |
| 数据源无 CAS（B 级） | `update` 忽略 `expectedConfigVersion` ⇒ 永不 `CONFLICT`，`StrategyView.casSupported=false` |
| 写穿顺序 | `change` 成功后本机 `find` 立即新值；并发对账不会把旧值回填回来（`putIfNewer` 保护） |
| 自举参数 | `maxSize` 默认 1000；改配置需重启；启动日志打印实际生效值 |

## 19. 模块归属与分期

| 模块 | 内容 |
|---|---|
| `cache4j-core` | 全部 SPI 与模型：`CacheStrategyRepository`（门面接口）、**`CacheStrategyDataSource`（数据源 SPI）**、`CacheStrategyAdmin`、`NamespacePolicy`、`StoredPolicy`、`StrategyChange`、`ChangeResult`、`StrategyView`、`StrategySelfCacheOptions`、`PolicyLookup`（三态） |
| **`cache4j-strategy`（新增）** | `CachedCacheStrategyRepository`（门面实现）：用本库的 `TwoLevelCache`（namespace=`cache4j.strategy`，`STATIC`）承载策略读取与写穿；把 `CacheStrategyDataSource` 当作回源；解析三层优先级；自举校验（禁止 NONE/DYNAMIC）。依赖 `core` + `local` + `central`，**不依赖** `spring*` |
| `cache4j-spring-redis` | **不新增**：不开箱数据源（R8），也不加 `writeIfRevision`（业务端纯读、写穿用普通 `put`/`putIfNewer`）。数据源由使用者自己按 §20.2 实现 |
| `cache4j-spring` | 端点（可选 Actuator）、`@CacheNamespace` 扫描接入 |
| `cache4j-starter` | 默认装配与开关配置（含"仓库自身策略被配成 NONE/DYNAMIC 即启动失败"的校验） |

| 期 | 内容 |
|---|---|
| **P1** | 仓库门面 SPI + `CacheStrategyDataSource` SPI（`register`/`update`）+ `PolicyLookup` 三态 + 解析优先级 + 业务端装配 |
| **P2** | 管理 SPI（分页/详情/change）+ 校验 + 审计日志 + 指标 |
| **P3** | `CachedCacheStrategyRepository`（自举缓存两层）+ 5s 版本对账传播（多节点一致） |
| **P4** | 管理服务侧能力（分页/详情/change/审计日志）+ `cache4j-starter` 装配 |

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

写有两条路径，分别对应 `register` 与 `update`：

```text
register  →  HSETNX cache4j.policy:<ns> data <编码>   （存在则返回 0 ⇒ false，一个字都不改）
update    →  Lua 比较 configVersion 后写入
```

`update` 的 Lua（`cache4j.policy:cas`）：

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

## 21. 已拍板的契约细节

| # | 问题 | 结论 |
|---|---|---|
| R1 | 变更请求传 `patch` 还是完整对象 | **完整 `NamespacePolicy`**：运维本来就是"列表→详情→改几项→提交"，手里有完整对象；强类型无拼写风险，想恢复默认就直接填默认值 |
| R2 | 是否存在"单节点模式" | **不存在**：一律按"N 个客户端连同一套共享存储（N≥1）"设计；版本号必须来自共享存储；"没有数据源"只能等于"完全不启用仓库" |
| R3 | 管理端点部署形态 | **独立的管理服务**，业务 JVM 一律不暴露管理端点；库只提供 SPI 与日志 |
| R4 | 审计是否持久化 | 库**只打结构化日志**；审计持久化、历史回看归管理服务 |
| R5 | 声明与生效不一致是否告警 | **不告警**（一次都不要）：接了仓库就意味着业务允许两者不一致；`declaredStrategy` 仅作展示 |
| R6 | 数据源写入方法 | **拆两个**：`register`（初始注册，已存在返回 false、不覆盖）+ `update`（乐观锁更新，empty = 冲突） |
| R7 | 是否强制数据源支持 CAS | 尽力而为：只有 A（真 CAS）/ B（last-write-wins）两级，用 `StrategyView.casSupported` 暴露 |
| R8 | 是否提供开箱数据源 | **不提供**，谁接谁实现；`cache4j-spring-redis` 不为策略新增任何能力 |

### 仍开放

| # | 问题 | 备选 | 倾向 |
|---|---|---|---|
| Q1 | 业务用了未声明的 namespace（如 `@Cacheable("order:detail")` 但没人声明过） | 启动/首次访问报错 / 按内建默认自动创建 / 只告警返回 null | **报错**（声明是强制清单，否则策略治理形同虚设） |
| Q2 | 数据源里有、代码里没声明的 namespace（孤儿配置） | 装配 / 忽略 / 忽略并标记 | 不装配，`list()` 标 `orphan=true` 并告警 |
| Q3 | 多应用共享同一数据源时 namespace 重名 | 加 appId 前缀 / 每个应用独立数据源 | 倾向**独立数据源或独立 DB**，不在 key 上加前缀（避免和业务 cache4j 实例的 namespace 语义打架） |
| Q4 | 数据源里一条损坏记录是否阻塞全站启动 | fail-fast / 跳过并标记 | **跳过 + 告警 + 视图标 `broken`**（不让一条坏配置挡住整站），但不自动修复 |
| Q5 | 是否需要"变更历史"查询 API | 不提供（归管理服务）/ 提供 | **不提供**（R4 已定），管理服务自己落审计表 |
| Q6 | `GlobalSpec` 改 QPS 后，候选阈值派生公式需重算 | — | 属 LOCAL 层（`LOCAL.md`），要在实现前重算并回写 |
| Q7 | `StrategyView.dynamic` 对非 DYNAMIC 策略的展示 | `null` / 保留并标 `inactive` | 保留并标 `inactive`（切 STATIC 再切回来不丢配置） |
| Q8 | `description` / `reason` 的日志注入与长度 | 不限 / 限长并禁控制字符 | **限 128 且禁控制字符**（写日志前转义） |
