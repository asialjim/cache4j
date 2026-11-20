# 缓存策略仓库契约 & 仓库管理契约

> 状态：**契约草案，未实现**。
> 上游：`STRATEGY.md`（声明与发现：枚举三字段、`META-INF/services` 与 Spring 扫描、转换链路、优先级、运行时切换机制）。
> 下游：`DESIGN.md` / `LOCAL.md` / `CENTRAL.md`（缓存运行时本身）。
> 本文只定"仓库里有什么、谁能改、怎么改、改完怎么保证一致"，不讨论缓存读写路径。

## 0. 定位

| 组件 | 职责 | 不负责 |
|---|---|---|
| **声明**（`CacheStrategyDecl`） | 开发者表达"要一个叫什么、初始什么策略、干什么用"的缓存 | 任何参数 |
| **仓库**（本文 §1–§7） | 持有**生效配置**，是"当前这个 namespace 到底怎么配"的唯一权威 | 缓存实例的创建与读写 |
| **管理**（本文 §8–§15） | 给出变更入口、校验、审计、观测 | 鉴权（使用方的 HTTP 层负责） |

**核心不变量（贯穿全文）**

1. 仓库是生效值的**唯一权威**；声明值只作初始兜底与审计对照。
2. 生效值解析顺序：**仓库 → 声明 → 内建默认**；三层都缺才算错误。
3. **没有仓库也能跑**：不实现任何仓库实现时，声明 + 内建默认即可装配，不报错。
4. **不提供 reset**：要回退就再改一次，审计留痕优先于"一键还原"。
5. **无后台线程**：变更传播挂在访问路径上（§5.4），不引入调度器。
6. **不做半应用**：任何一步失败，生效值不变。

---

# 第一部分：仓库契约（Repository Contract）

## 1. SPI 全景

```java
/** 仓库：进程内生效配置的唯一权威。 */
public interface CacheStrategyRepository {

    // ---------- 启动期 ----------

    /**
     * 登记一条声明（来自枚举）。幂等：同一 namespace + 同一来源重复登记无副作用。
     * 不同来源（两个枚举 / 枚举与程序化定义）声明同一 namespace ⇒ 抛 CacheException。
     */
    void declare(CacheStrategyDecl decl, DeclSource source);

    /** 登记完毕，解析并冻结初始生效值。返回被解析出的 namespace 列表（保序）。 */
    List<String> bootstrap();

    // ---------- 读取（幂等、无副作用） ----------

    /** @return 生效策略；未登记返回 empty */
    Optional<NamespacePolicy> find(String namespace);

    /** @return 全部生效策略（保序：按 bootstrap 解析顺序） */
    List<NamespacePolicy> all();

    /** @return 全部 namespace 的 epoch；用于变更传播的脏检查（必须是单次往返） */
    Map<String, Long> epochs(Collection<String> namespaces);

    // ---------- 变更（管理侧唯一入口） ----------

    /** 提交一次变更。见 §9。 */
    ChangeResult change(StrategyChange change);

    // ---------- 变更传播 ----------

    /**
     * 若中央 epoch 已前进，则重新解析该 namespace 的生效值并返回；否则返回 empty。
     * 由访问路径按 §5.4 触发，也可由适配层（如 Spring @Scheduled）主动调用。
     */
    Optional<NamespacePolicy> refresh(String namespace);
}
```

**契约约束**

- `find` / `all` / `epochs` **必须无副作用**（不得因读而创建、修复或写回任何状态）；
- `declare` / `bootstrap` / `change` / `refresh` **必须线程安全**；同一 namespace 的变更串行生效；
- 所有方法**不得抛受检异常**；失败一律 `CacheException`；
- `refresh` 在中央不可用时**不得**改动生效值（见 §6.3）。

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
| `epoch` | `long` | ≥ 1，单调递增 | 中央生成 |
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
4. 解析结果必须**冻结**为不可变对象，`find` 返回的是同一份快照（同一 epoch 内 identity 稳定，
   便于上层做 `==` 快速判断"换没换过"）。

## 4. 存储后端 SPI（可选）

仓库可挂一个持久化后端以实现**多节点一致**；不挂则是单节点内存模式。

```java
public interface CachePolicyStore {

    /** @return 该 namespace 的持久化记录；不存在返回 empty */
    Optional<StoredPolicy> load(String namespace);

    /** @return 全部 namespace（启动对账与运维视图用；允许分页实现） */
    List<StoredPolicy> loadAll();

    /**
     * 乐观锁写入：仅当存储里的 epoch == expectedEpoch 时成功并返回新 epoch；
     * 否则返回 empty（冲突）。epoch 必须由存储生成，客户端不得自增。
     */
    Optional<Long> save(StoredPolicy policy, long expectedEpoch);

    /** @return namespace → epoch（单次往返，变更传播的脏检查用） */
    Map<String, Long> epochs(Collection<String> namespaces);
}
```

**`StoredPolicy` 与 `NamespacePolicy` 的区别**：前者是**持久化形态**（字段可缺省、可带 schema 版本），
后者是**解析后的完整生效值**。二者不得互相替代：

- 存储里只写"与内建默认不同"的字段是允许的（省空间），但解析时必须补齐默认；
- `StoredPolicy` 必须能表达"这条记录没写 description"，以便识别"运维没改过描述"。

**无 Store 时的行为**：单节点内存模式，`epoch` 由进程内 `AtomicLong` 生成；重启后回到声明值。
文档与启动日志必须明确标注当前模式（见 §7）。

## 5. 一致性与线程模型

### 5.1 保证

| 保证 | 含义 |
|---|---|
| **读己之写** | `change` 成功返回后，同进程内 `find` 必须立即返回新值 |
| **最终一致** | 其他节点在 `strategyCheckInterval`（默认 5s，可配）内看到新值 |
| **epoch 单调** | 同一 namespace 的 epoch 只增不减；不同 namespace 的 epoch 相互独立 |
| **无部分应用** | 任何失败路径下，生效值与 epoch 都不前进 |
| **不可变快照** | 解析结果不就地修改；变更 = 换引用 |

### 5.2 并发

- 同一 namespace 的 `change` 串行（内部锁或 CAS 重试），避免"两个运维同时改"产生中间态；
- `change` 与 `refresh` 可并发；`refresh` 发现 epoch 前进后**重新解析并整体替换**，不做字段级合并；
- 读路径（`find`）必须**无锁**（volatile 快照 + 不可变对象）。

### 5.3 无后台线程

仓库自身**不得**创建线程、定时器、线程池。变更传播有两个触发源：

1. **访问驱动**（默认）：每次读入口一次 volatile 比较，到点才发一次 `epochs(...)` 往返；
2. **适配层主动**：Spring `@Scheduled`、管理端点收到变更后主动 `refresh`。

### 5.4 变更传播（访问驱动）

```text
每 namespace 维护：
    nextStrategyCheckNanos   // 下次允许检查的时间点
    checking                 // CAS 门控，避免同一节点并发打中央

读入口：
    r = snapshot.get()                                  // volatile 读，绝大多数请求到此为止
    if (now - r.nextCheckAt < 0) → 继续正常读写
    if (!checking.compareAndSet(false, true)) → 继续（别的线程在查）
    try   { if (repo.refresh(namespace) 有变化) → 走「运行时切换」流程 }
    finally { checking.set(false); nextCheckAt = now + strategyCheckInterval }
```

- `strategyCheckInterval` 默认 5s（仓库可配，与 `version.checkInterval` 相互独立）；
- 检查失败（中央不可用 / 解析失败）**不得**清空生效值，只累加 `strategyCheckErrorCount` 并按
  退避推迟下次检查（退避 ≥ 检查间隔，避免故障期加压）。

## 6. 失败语义

| 场景 | 必须的行为 |
|---|---|
| **无 Store** | 单节点模式；启动日志明确标注；不报错 |
| Store 不可达（启动期） | **必须**用"声明 + 内建默认"装配成功，并打 `warn`（不得启动失败，除非 `failClosed` 配置为 true） |
| Store 不可达（运行期 `refresh`） | 保持当前生效值；`strategyCheckErrorCount++`；按退避推迟；**不得**回落到声明值 |
| 存储记录损坏（字段解析失败） | 同"不可达"，且**不得**覆盖存储（不做自动修复） |
| 变更持久化失败 | `change` 返回 `REJECTED`，生效值不变（先持久化后生效，见 §9.4） |
| 目标配置校验失败 | `change` 返回 `REJECTED` + 具体字段与原因，生效值不变 |
| 运行时装配失败（如 `DYNAMIC+GLOBAL` 缺 coordinator） | `change` 返回 `REJECTED`；若发生在 `refresh`（他人提交的配置本机装配不了）⇒ 保持旧值 + `error` 日志 + 指标，**不得**降级策略 |

**禁止**：任何失败路径下"猜一个策略继续跑"。要么明确用旧值，要么明确失败。

## 7. 部署模式

| 模式 | Store | 生效值来源 | 重启后 | 多节点一致 | 适用 |
|---|---|---|---|---|---|
| **单节点** | 无 | 声明 + 内建默认 | 回到声明值 | 不适用 | 开发/测试/单实例 |
| **多节点** | Redis 等 | Store → 声明 → 默认 | **保持运维改过的值** | 是（最终一致） | 生产 |

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

1. `find` / `all` / `epochs` 无副作用；
2. 同一 epoch 内 `find` 返回同一实例（identity 稳定）；
3. 解析不做字段级合并（整条生效）；
4. 变更 = 换引用，不就地改对象；
5. 同一 namespace 的变更串行，epoch 单调；
6. 任何失败不改生效值、不推进 epoch；
7. `refresh` 失败不回落到声明值；
8. 仓库不创建线程；
9. 无 Store 时仍能装配（单节点模式）；
10. 仓库不持有缓存实例，只持有配置。

---

# 第二部分：仓库管理契约（Admin Contract）

## 10. 管理 SPI

```java
/** 运维管理入口。库只提供动作，不暴露 HTTP 端点、不做鉴权。 */
public interface CacheStrategyAdmin {

    /** @return 全部 namespace 的视图（含声明值、生效值、epoch、来源、审计元数据） */
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
        long expectedEpoch,        // 必填，乐观锁；不知道当前值时必须先 find()
        String operator,           // 必填，审计；鉴权在调用方完成
        String reason,             // 必填，审计
        long requestedAtMillis     // 必填，服务端可校验"过期请求"（如超过 5 分钟拒绝）
) { }

/** 变更结果。三态 + 明确的下一步。 */
public record ChangeResult(
        Outcome outcome,           // APPLIED / NO_CHANGE / CONFLICT / REJECTED
        long epoch,                // 变更后的 epoch（CONFLICT 时为**当前** epoch）
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
        long epoch,
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
                     │ 4. 乐观锁检查（expectedEpoch）│ 不匹配 → CONFLICT（返回当前 epoch）
                     ├──────────────────────────────┤
                     │ 5. 试装配（构建新 Runtime）   │ 失败 → REJECTED（生效值不变）
                     ├──────────────────────────────┤
                     │ 6. 持久化（Store.save）       │ 失败/冲突 → REJECTED / CONFLICT
                     ├──────────────────────────────┤
                     │ 7. 原子提交（换引用）         │ 唯一提交点
                     ├──────────────────────────────┤
                     │ 8. 广播 + 审计 + 指标         │ 失败只告警，不回滚已生效值
                     └──────────────────────────────┘
```

顺序的硬要求：

- **先持久化（6）后生效（7）**：存储失败时本机不生效，避免"单节点自己变了、其他节点没变"；
- **第 5 步试装配**：先造出新 Runtime 再提交，确保"提交后不可能因装配失败而空转"；
- **第 8 步失败不回滚**：变更已生效，广播失败只影响其他节点的传播时延（下一轮检查会补齐）。

## 13. 幂等、乐观锁与并发

| 机制 | 契约 |
|---|---|
| **幂等键** | `requestId` 必填；同一 `requestId` 重复提交返回**首次结果**（不重复生效）；保留窗口由实现决定（建议 ≥ 10 分钟） |
| **乐观锁** | `expectedEpoch` 必填且必须等于当前 epoch；不匹配返回 `CONFLICT` + 当前 epoch，调用方重新 `find` 后再试 |
| **并发同 key** | 同一 namespace 的 `change` 串行；不同 namespace 并行 |
| **NO_CHANGE** | 目标配置与当前逐字段相等 ⇒ 不写存储、不换 Runtime、epoch 不变 |
| **epoch 生成** | 只能由 Store（多节点）或进程内 `AtomicLong`（单节点）生成；**调用方不得指定** |
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
| `fromEpoch` → `toEpoch` | 提交前后 |
| `diff` | 逐字段 diff（只记变化的键，值做脱敏？——参数无敏感数据，直接记） |
| `outcome` + `message` | 结果 |

**日志格式**（沿用全项目约定 `[主题]-子过程-具体信息`）：

```
[策略仓库]-变更-user:detail-APPLIED，epoch 7→8，strategy STATIC→DYNAMIC，maxSize 1000→5000，操作人=ops:zhangsan，原因=大促热点，requestId=9f2c…
[策略仓库]-变更-user:detail-REJECTED，DYNAMIC+coordination=GLOBAL 缺少 HotKeyCoordinator，epoch 仍为 8，操作人=ops:zhangsan
[策略仓库]-变更-user:detail-CONFLICT，期望 epoch=7，当前=8，操作人=ops:lisi
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
long strategyEpoch();              // 当前生效 epoch（gauge 语义）
long strategySwitchCount();        // 成功切换次数
long strategySwitchRejected();     // 被拒次数
long strategyChangeConflict();     // 乐观锁冲突次数
long strategyCheckCount();         // 变更检查次数（访问驱动）
long strategyCheckErrorCount();    // 检查失败次数
long strategyRefreshLatencyMax();  // 变更在节点上的最大传播延迟（近似：检查到生效的间隔）
```

运维视图（`StrategyView`）必须能直接回答三个问题：

1. 这个 namespace 现在是什么策略、什么参数？（生效值）
2. 代码里声明的是什么？与生效值是否一致？（`declaredXxx` + `consistentWithDeclared`）
3. 谁在什么时候因为什么改的？（`updatedBy` / `updatedAtMillis` / `reason` / `epoch`）

## 18. 测试矩阵

| 类别 | 用例 |
|---|---|
| 解析优先级 | 有仓库/无仓库/仓库缺该 namespace；内建默认逐字段验证 |
| 无 Store 模式 | 装配成功、日志标注、重启回到声明值 |
| Store 不可达 | 启动期降级成功；运行期 `refresh` 保持旧值 + 计数 |
| 幂等 | 同一 `requestId` 重复提交只生效一次、返回同一结果 |
| 乐观锁 | 两个 `change` 同 `expectedEpoch` ⇒ 一个 APPLIED 一个 CONFLICT |
| NO_CHANGE | 逐字段相同 ⇒ 不写存储、不换 Runtime（identity 断言）、epoch 不变 |
| 试装配失败 | 缺 coordinator ⇒ REJECTED，生效值与 epoch 不变 |
| 持久化失败 | Store 抛异常 ⇒ REJECTED，本机不生效 |
| 未知 patch 键 | 拼写错误 ⇒ REJECTED（不静默忽略） |
| 派生值 | 改 `centralNullTtl` 或 `localNullTtlDivisor` ⇒ `localNullTtl` 按 `min(localTtl, c/N)` 变化 |
| 传播 | 节点 A 改、节点 B 在 `strategyCheckInterval` 内生效；B 无流量时不生效（访问驱动语义） |
| 审计 | APPLIED/REJECTED/CONFLICT 各一条日志，字段齐全，不出现业务 key/值 |
| 边界 | `namespace` 改名、`strategy` 非法值、`patch` 空、`reason` 空、过期 `requestedAtMillis` |

## 19. 模块归属与分期

| 模块 | 内容 |
|---|---|
| `cache4j-core` | 全部 SPI 与模型：`CacheStrategyRepository`、`CachePolicyStore`、`CacheStrategyAdmin`、`NamespacePolicy`、`StoredPolicy`、`StrategyChange`、`ChangeResult`、`StrategyView`；`InMemoryCacheStrategyRepository`（单节点 + 无 Store） |
| `cache4j-spring-redis` | `RedisPolicyStore`（§20 布局） |
| `cache4j-spring` | 端点（可选 Actuator）、扫描接入、`refresh` 的 `@Scheduled` 触发（可选） |
| `cache4j-starter` | 默认装配与开关配置 |

| 期 | 内容 |
|---|---|
| **P1** | 仓库 SPI + `InMemoryCacheStrategyRepository`（无 Store）+ 解析优先级 + 启动装配 |
| **P2** | 管理 SPI + `change` 状态机 + 校验 + 审计 + 指标（单节点热更） |
| **P3** | `CachePolicyStore` + `RedisPolicyStore` + 访问驱动检查（多节点一致） |
| **P4** | 端点、`@Scheduled` 触发、`cache4j-starter` 装配 |

## 20. 中央存储布局（Redis）契约

```
__strategy:<namespace>            HASH（不设 TTL：策略必须先于数据存在）
    schema        1
    strategy      STATIC | DYNAMIC | NONE
    description   用户详情，读多写少
    maxSize       1000
    localTtlMs    30000
    nullDivisor   10
    version       check=5000;jitter=100;backoff=200;batch=1000;partitions=16
    dynamic       qps=100;window=10000;buckets=10;maxHotKeys=1000;candidate=1000;
                  cooldown=30000;reportPartitions=16;reportBatch=16;coordination=LOCAL
    centralTtlMs     1800000
    centralNullTtlMs 30000
    failOpen      1
    epoch         8
    updatedAt     1758278400000
    updatedBy     ops:zhangsan
    reason        大促期间降低回源压力
```

硬性要求：

1. **命名空间隔离**：`__strategy:` 前缀与数据 key、版本表（`__rev`）互不重叠；
   `clear(namespace)` **不得**删除 `__strategy:`（否则清缓存连策略一起清掉）；
2. **epoch 只能由存储递增**（`HINCRBY`），且必须在同一事务/Lua 内与字段写入一起完成，
   保证"epoch 前进"与"字段生效"是原子的；
3. **写入必须做乐观锁**：`expectedEpoch` 与当前值比较，不匹配则不写；
4. **只写发生变化的字段也可以，但读取必须能区分"未设置"与"设置为默认值"**
   （建议：存全量，简单且可读）；
5. **不做自动修复**：字段解析失败时不得回写，避免把运维的错误配置"修"成静默的默认值。

## 21. 开放的契约问题

| # | 问题 | 备选 | 倾向 |
|---|---|---|---|
| R1 | `change` 的 `patch` 用扁平 `Map<String,String>` 还是强类型对象 | 扁平字符串 / 每种变更一个强类型 record | **扁平 + 白名单键**（运维脚本友好），但要求键名稳定并进契约 |
| R2 | 单节点模式下是否也要落本地文件（重启不丢） | 不落 / 落一个 properties | 不落（单节点就是开发/测试用），多节点用 Store |
| R3 | `cache4j.strategy.admin.enabled` 默认值 | 默认开 / 默认关 | **默认关**（生产要显式开启管理端点），但需在 starter 里方便打开 |
| R4 | 审计是否也要落 Store（持久化审计） | 只打日志 / 也存一份 | 只打日志（日志系统负责留存），Store 只存当前值 |
| R5 | `StrategyView.consistentWithDeclared` 不一致时是否告警 | 只展示 / 启动 + 每次检查告警 | 启动 `warn` 一次 + 视图展示，**不**每轮检查刷日志 |
