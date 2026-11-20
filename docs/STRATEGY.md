# 缓存策略：声明式初始化与运行时切换

> 状态：**设计稿，未落地**。本文只定义方案与取舍，不含实现。
> 关联：`DESIGN.md`（版本对账、门面编排）、`LOCAL.md`（本地 SPI）、`ADAPTERS.md`（框架适配）。

## 1. 背景与目标

现状有两个缺口：

1. **声明与初始化是硬编码**：每个 namespace 的策略（`NONE` / `STATIC` / `DYNAMIC`、`maxSize`、
   `localTtl`、`version`、`dynamic`）都要在装配代码里逐条手写 `LocalCacheSpec` / `CentralCacheSpec`。
   业务方只知道自己"要一个用户详情缓存"，不该关心 `reconcilePartitions` 这类参数。
2. **策略不可热更**：`LocalCacheSpec` 与 `CentralCacheSpec` 是不可变记录，`DefaultTwoLevelCache`
   在构造时把本地缓存实例、分区索引、对账信号量都固化成字段。线上要把某个 namespace 从
   `NONE` 调成 `STATIC`（或反向），只能重启。

目标：

- **声明式**：用户写一个枚举（实现 `CacheStrategyDecl`），库自己发现、翻译、校验、注册、装配；
- **可热更**：运维通过库提供的 SPI 把某个 namespace 的策略从任意值改到任意值，**不停机**，
  且在途请求不打补丁、不与新状态混合；
- **可多节点一致**：一次变更对所有节点生效（持久化在中央，节点感知）；
- **可审计**：每次变更都留下"谁、什么时候、从什么改成什么、为什么"。

非目标（本设计明确不做）：

- 不做动态改**中央侧**参数（Redis TTL / 连接 / 序列化）：那是基础设施变更，走重新装配；
- 不做策略的"自动调参"（自适应 `maxSize`、自动升降级）：只接受**人为声明**或**人为变更**；
- 不在 `core` 里引入包扫描、web 端点、Redis 客户端：核心仍是框架无关、零依赖。

## 2. 术语

| 术语 | 含义 |
|---|---|
| **声明（Decl）** | 用户写的枚举，描述一个 namespace 想要什么策略 |
| **定义（Definition）** | 仓库补全后的 `(LocalCacheSpec, CentralCacheSpec)` 对，即 `CacheDefinition` |
| **声明值 / 生效值** | 声明值 = 枚举里写的（`declaredStrategy`）；生效值 = 仓库判定后真正装配的（`activeStrategy`）。**运维只能改生效值** |
| **注册表（Registry）** | 进程内的"namespace → 当前生效定义"登记处，是热更的落点 |
| **运行时（Runtime）** | 一次定义所对应的全部可变对象：本地缓存实例、分区索引、对账信号量、生效 `partitions` |
| **epoch** | 策略版本号，单调递增；用于乐观锁、脏检查与日志对齐 |
| **句柄（Handle）** | 业务持有的 `TwoLevelCache` 引用。**始终不变**，内部指向当前 `Runtime` |

## 3. 总体架构

```
  用户代码                        cache4j 核心                        运维
┌──────────────┐   ServiceLoader  ┌────────────────────┐   change()  ┌─────────────┐
│ enum UserCache│ ───────────────► │ CacheStrategyLoader│ ──────────► │ 注册表       │
│ implements    │                  └─────────┬──────────┘             │ Registry    │
│ CacheStrategyDecl     │                            │ 翻译 + 校验            └──────┬──────┘
└──────────────┘                            ▼                              │
                                  ┌────────────────────┐                    │ set(runtime)
                                  │ 定义 CacheDefinition│                    ▼
                                  └─────────┬──────────┘          ┌──────────────────┐
                                            │ 装配                 │ StrategyRuntime  │
                                            ▼                     │ (AtomicReference)│
                                  ┌────────────────────┐          └────────┬─────────┘
                                  │ 门面 TwoLevelCache │◄─── 读写全程用 r ─┘
                                  └────────────────────┘
                                            ▲
                    多节点同步：中央策略存储（Redis HASH + epoch），访问驱动检查
```

三层职责：

- **声明层**：只描述"想要什么"，不碰实例；
- **注册表层**：唯一权威的"当前生效定义"，负责校验、版本化、持久化（可选）；
- **运行时层**：把定义变成实例，并**可被原子替换**。

## 4. 一、声明式初始化

### 4.1 声明形态：枚举 + `CacheStrategyDecl`（三个成员，全部必需）

用户只写枚举，接口只规定"**怎么定义一个缓存策略**"：

```java
@Getter                    // Lombok 生成 getNamespace() / getStrategy() / getDescription()
@CacheNamespace            // 供 Spring 包扫描发现；core 侧走 META-INF/services，不依赖它
public enum UserCacheDecls implements CacheStrategyDecl {

    USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情，读多写少"),
    USER_PROFILE("user:profile", LocalStrategy.DYNAMIC, "用户档案，存在热点"),
    ORDER_SNAPSHOT("order:snapshot", LocalStrategy.NONE, "订单快照，仅中央，不强求本地");

    private final String namespace;
    private final LocalStrategy strategy;
    private final String description;

    UserCacheDecls(String namespace, LocalStrategy strategy, String description) {
        this.namespace = namespace;
        this.strategy = strategy;
        this.description = description;
    }
}
```

```java
public interface CacheStrategyDecl {

    /** ① 缓存命名空间 */
    String getNamespace();

    /** ② 初始本地策略（<strong>声明值</strong>，可被仓库覆盖） */
    LocalStrategy getStrategy();

    /** ③ 命名空间描述信息；<strong>必须非空</strong>，否则运维无从判断这个缓存干什么用 */
    String getDescription();
}
```

**契约用 getter 命名**：枚举里只声明三个字段 + 构造器，配合 Lombok `@Getter` 即可满足接口，
不需要手写三个 `@Override`。库**不依赖 Lombok**——它只是"字段即实现"的最省事方式，
不用 Lombok 的团队手写三个 getter 也一样；接口本身没有任何 Lombok 痕迹。

要点：

- **三个成员全部必需，没有默认值、没有可选参数**。容量、时长、版本对账、热升降阈值等
  一律不在这里——它们属于**仓库**（§5），由运维按 namespace 独立配置；
- `description` **必须非空**（`trim()` 后为空 ⇒ 启动失败）。它是运维视图与启动日志里唯一的人话，
  宁可启动时报错，也不要出现"这个 namespace 是干什么的？"的运维事故；
- 枚举天然单例、有 `name()`，适合审计（日志直接写 `UserCacheDecls#USER_DETAIL`）；
- 一个枚举可声明多个 namespace；不同枚举声明同一 namespace 属于冲突，启动失败（§4.4）。

**为什么把参数都推给仓库**：同一个 `user:detail` 在不同业务、不同时期需要不同容量与 TTL，
写在代码里就要发版；而"改一个 namespace 的容量"是运维动作，不是代码变更。枚举只表达
"**我想要一个叫 X 的缓存，起初用 STATIC 策略，用途是 Y**"，剩下的由仓库补全并可在运行时调整。

**不开档位**：早期不考虑 `SMALL/LARGE` 之类的档位枚举。未配置仓库时用**唯一一套内建默认**
（见 §4.3），够用且心智负担最小；真要调优就去仓库里按 namespace 配。

### 4.2 发现机制：三选一，结论是"两条腿"

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **按 `ServiceLoader` 约定读文件**（`META-INF/services/...CacheStrategyDecl`） | 零依赖、可预测；文件格式是通用约定，工具都认识 | 需要一行 `resources` 文件 | **core 默认**（加载逻辑自研，见 4.2.1） |
| **包扫描**（`@CacheNamespace` + 扫描器） | 用户零配置，贴注解即可 | 自研扫描要处理 jar/目录/模块路径/类加载器，易错；启动耗时 | **Spring 适配层**（用 Spring 自带扫描能力），core 不做 |
| **多态自注册**（枚举静态块调 `Registry.register`） | 用户零配置 | 类不被加载就不会注册，**漏注册是静默的** | 不作主路径，见 §4.2.4 |

因此：

- `cache4j-core` 提供 `CacheStrategyLoader.load()`：按 `ServiceLoader` 的**文件约定**读声明，
  取枚举常量 → 交给仓库 `materialize()` 补全参数 → `validate()` → 注册；
- `cache4j-spring` 额外提供注解扫描（`@CacheNamespace` 标注的枚举），把扫到的实例一并交给同一个注册表。
  多条路径幂等合并，冲突检测见 4.4。

### 4.2.1 为什么不能直接用 `ServiceLoader.load(CacheStrategyDecl.class)`

`ServiceLoader` 创建 provider 的唯一方式是**反射调用无参构造器**：

```java
// ServiceLoader 内部（简化）
Class.forName(className).getDeclaredConstructor().newInstance()
```

而 `java.lang.reflect.Constructor#newInstance` 对枚举类型**直接拒绝**：

```text
IllegalArgumentException: Cannot reflectively create enum objects
```

枚举常量由 JVM 在类初始化时创建，反射新建会破坏单例语义，JDK 明确禁止。所以
`ServiceLoader.load(CacheStrategyDecl.class)` 遇到枚举必然抛 `ServiceConfigurationError`。

**结论：文件格式、路径、每行一个类名的约定全部沿用，但加载逻辑自研**：

```java
// CacheStrategyLoader 内部（简化）
for (String line : lines(entry)) {                       // 跳过空行与 # 注释
    Class<?> type = Class.forName(line, false, loader);
    if (!CacheStrategyDecl.class.isAssignableFrom(type)) {
        throw invalid(entry + " 第 " + lineNo + " 行：" + line + " 不是 CacheStrategyDecl 实现");
    }
    if (type.isEnum()) {
        for (Object constant : type.getEnumConstants()) { // ← 枚举走这里，不反射构造
            decls.add((CacheStrategyDecl) constant);
        }
    } else {
        decls.add((CacheStrategyDecl) type.getDeclaredConstructor().newInstance());  // 兼容非枚举写法
    }
}
```

用户体感：**一个枚举 + 一行 resources 文件**。

```text
src/main/resources/META-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl
────────────────────────────────────────────────────────────────────────────
# 每行一个全限定类名，支持 # 注释与空行
com.acme.cache.UserCacheDecls
com.acme.cache.OrderCacheDecls
```

### 4.2.2 加载器的三条实现细节

1. **必须读取全部同名资源再合并**：用 `ClassLoader.getResources(...)` 遍历所有 URL，
   不要用 `getResource(...)`（只返回第一个，多 jar 场景会静默漏掉一半声明）；
2. **类加载器可注入**：默认 `Thread.currentThread().getContextClassLoader()`，容器 / 插件类加载器
   场景允许显式传入，否则容易找不到类；
3. **零文件兜底**：额外支持显式传入枚举类 —— `options.declEnums(UserCacheDecls.class, ...)`，
   供单元测试与不愿维护 resources 文件的使用方使用。
   三条来源（文件 / 注解扫描 / 显式传入）汇入同一个注册表，同一枚举重复出现只注册一次。

### 4.2.3 关于 GraalVM / native image 的准确表述

**先说清一件事：本方案并<u>不</u>免反射。** `Class.forName` 与 `getEnumConstants()` 都是反射，
native image 的封闭世界假设（closed-world）要求构建期就能确定可达性，否则目标类会被裁掉、
运行时报 `ClassNotFoundException`。所以本方案与"包扫描"的差别**不在有没有反射**，而在下面两点：

| 限制 | 包扫描 | 本方案（静态类名清单） |
|---|---|---|
| 运行期遍历 classpath（列 jar、推 `.class` 文件名、枚举包） | **不可行**——native image 产物是单个可执行文件，没有 classpath 目录可扫 | 不做遍历 |
| 反射可达性 | 需要，且**目标不可预知**（扫到谁才知道） | 需要，但**目标是一份静态清单，可逐条精确注册** |

因此准确的卖点是：**反射面可枚举、可静态注册；运行期零 classpath 遍历。**

native image 下按三档处理，由易到难：

| 档 | 做法 | 反射 | native image 成本 |
|---|---|---|---|
| **1. 声明文件**（默认） | `META-INF/services/...CacheStrategyDecl` 一行一个枚举 | `Class.forName` + `getEnumConstants` | 需要 hints，但清单是静态的，**可由库自动生成** |
| **2. 显式传入**（兜底） | `options.declEnums(UserCacheDecls.class)` | 同上 | 同上 |
| **3. 生成的注册代码**（真零反射） | 由编译期/AOT 生成 `Cache4jDecls.register(registry)`，内部直接 `UserCacheDecls.values()` | **无** | **零配置** |

第 1、2 档的 hints 库自己出（用户不必手写 `reflect-config.json`）：

```java
// cache4j-spring：Spring 6 AOT 会自动调用 RuntimeHintsRegistrar
public final class Cache4jRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader loader) {
        for (String className : CacheStrategyLoader.declaredClassNames(loader)) {
            hints.reflection().registerType(TypeReference.of(className));   // 只需枚举类可达
        }
    }
}
```

纯 Java（无 Spring）用户拿到的是机械可生成的模板：清单里几个类就几个条目。

第 3 档才是字面意义的"对 native image 友好"：`values()` 是编译期静态引用，`register(...)` 由入口
显式调用，全链路无反射、无 hints、无 resources 文件。代价是多一个生成步骤（APT 或 AOT 入口代码）。

> **必须实测**：`getEnumConstants()` 在 native image 下的最小注册项（是否仅需类可达、是否还需要
> 常量数组标注）随 GraalVM 版本变化，文档不写死结论——测试计划里列了 native image 冒烟用例，
> 以实测结果回填本节的配置模板。

完整方案（障碍清单、库自带 hints、三档交付方式、使用方需配合的部分、验证与不承诺范围）见 **§12**。

### 4.2.4 自注册机制：可行形态与可靠性边界

**先说前提：Java 没有"列出某个接口的全部实现类"的原生能力。** 任何"自注册"最终都落在三件事之一：
① 有人**显式调用**注册方法；② 有**静态清单**可读（文件 / 生成的常量）；③ 有**扫描器**枚举 classpath。
所以问题不是"要不要自注册"，而是"**由谁保证那段注册代码被执行**"。

四种形态的对比：

| 形态 | 用户要写 | 可靠性 | 可测性 | native image | 结论 |
|---|---|---|---|---|---|
| **A. 静态块自注册**<br>`static { Registry.global().register(values()); }` | 只写枚举 | ❌ **不确定**（类不加载就漏） | ❌ 全局单例污染测试 | ❌ 类被裁则静默失效 | 只能当糖，不能当主路径 |
| **B. 显式 install**<br>`Cache4j.install(UserCacheDecls.values())` | 枚举 + 一行代码 | ✅ 确定 | ✅ 无全局状态 | ✅ 静态可达 | **纯 Java 主路径（P1）** |
| **C. 生成式清单（APT）**<br>注解处理器生成 `META-INF/services/...CacheStrategyDecl` | **只写枚举** | ✅ 确定 | ✅ | ✅ 清单静态 | **零配置首选（P2）** |
| **D. Spring 包扫描** | 枚举 + `@CacheNamespace` | ✅ 确定 | ✅ | ⚠️ 需 hints | **Spring 零配置（P4）** |

#### A 的三个坑（为什么不能当主路径）

1. **类不被加载 = 静默漏注册**。枚举只在被 `import`/引用/反射时才初始化。业务只在
   `@Cacheable("user:detail")` 里写了字符串，没人引用 `UserCacheDecls` → `static` 块永不执行 →
   启动后该 namespace 没有策略 → 行为随"某处是否恰好引用了这个枚举"而变，**这是最坏的一类 bug**。
2. **全局单例污染测试**：`Registry.global()` 是静态状态，两个测试类交叉注册后无法隔离，
   并行测试更糟。本库其余部分刻意避免静态可变状态（`CacheLogger.DIAGNOSED` 是唯一的特例，
   且只写不读业务语义）。
3. **注册顺序不确定**：`static` 块的执行时机取决于类初始化顺序。若 `values()` 里某个常量需要
   读取中央配置，会撞上"registry 还没建好"的窗口。

#### 推荐的组合（把自注册降级为"可选糖"）

```
主路径：B（显式 install） → P2 由 C（APT 生成清单）接管，用户彻底零配置
补充：  A（静态块）仍可写，但它只是把一个"待定池"填上，
        由 Registry 创建时统一认领；漏了也不致命（B/C/D 兜底）
零配置：D（Spring 注解扫描）
```

**"待定池"设计**（让 A 无害的关键）：

```java
/** 静态块只能往这里塞，不直接生效，避免"注册早于 registry 创建"和"顺序不确定"。 */
final class PendingDecls {
    private static final Set<CacheStrategyDecl> PENDING = ConcurrentHashMap.newKeySet();
    static void offer(Collection<CacheStrategyDecl> decls) { PENDING.addAll(decls); }
    static List<CacheStrategyDecl> drain() {                      // registry 创建时调用一次
        List<CacheStrategyDecl> out = new ArrayList<>(PENDING);
        PENDING.clear();
        return out;
    }
}
```

- `PENDING` 是幂等集合（同一枚举重复塞入只留一份）；
- registry 创建时 `drain()` 一次，与 B/C/D 的结果合并后统一做冲突检测（§4.4）；
- 进程内多 registry（多租户/测试）各自 drain 会互相抢 —— 因此 **drain 只允许一次**，
  多次创建 registry 时后续实例不再从池取（明确写进契约，避免"第二个 registry 空手而归"的困惑）。

#### 与热更的关系

自注册只解决"**初始声明从哪来**"。热更的权威始终是注册表（§5），与发现方式无关：
无论声明来自 A/B/C/D，`change()` 的操作对象都是同一个 `CacheStrategyRegistry`。

#### 分期

| 期 | 内容 |
|---|---|
| P1 | 形态 B（`Cache4j.install(...)`）+ 文件清单（§4.2.1）+ 待定池骨架 |
| P2 | 形态 C（APT 生成清单，`cache4j-processor` 可选依赖，不加也能手写文件） |
| P4 | 形态 D（Spring 注解扫描） |

### 4.2.5 从 `Class` 到可用策略：枚举常量到缓存策略的转换链路

不问来源（文件清单 / Spring 扫描 / 显式 install / 静态块），**最终都是拿到一个 `Class`**，
然后走同一条链路。这一步是本机制的技术核心，定死如下。

#### 4.2.5.1 取枚举常量的三种写法

| 写法 | 结论 |
|---|---|
| `type.asSubclass(CacheStrategyDecl.class).getEnumConstants()` | ✅ **采用**。`Class` 内建能力，返回全部常量（`CacheStrategyDecl[]`，类型安全），**不是反射构造**——读的是 JVM 已建好的常量数组，因此不受"枚举禁止反射实例化"的限制 |
| `type.getMethod("values").invoke(null)` | ❌ 不采用。需要额外注册方法 hints（native image 更重）、要拆 `InvocationTargetException`、语义绕 |
| `type.getDeclaredConstructor().newInstance()` | ❌ 不可行。对枚举直接抛 `IllegalArgumentException: Cannot reflectively create enum objects` |

> 同一枚举被多条来源发现时，用 `Class` 去重（`Set<Class<?>>`），**只转换一次**。

#### 4.2.5.2 转换链路（五步，顺序不可换）

```java
// ① 类型校验：顺序固定，报错才有信息量
if (!CacheStrategyDecl.class.isAssignableFrom(type)) {
    throw invalid(where + "：" + type.getName() + " 未实现 CacheStrategyDecl，实际接口="
            + Arrays.toString(type.getInterfaces()));
}
if (!type.isEnum()) {
    throw invalid(where + "：" + type.getName() + " 不是枚举，请改用 CacheDeclProvider");
}

// ② 取全部常量：一次拿全，类型安全
CacheStrategyDecl[] constants = type.asSubclass(CacheStrategyDecl.class).getEnumConstants();
if (constants == null) {                       // 防御：isEnum() 已挡，理论上不可达
    throw invalid(where + "：" + type.getName() + " 无法读取枚举常量");
}
if (constants.length == 0) {                   // 空枚举合法：没有任何声明
    LOG.debug(Subject.ASSEMBLY, "初始化", where + "：" + type.getName() + " 无任何常量，跳过");
    return List.of();
}

// ③ 逐个翻译：纯函数；错误信息必须能定位到具体常量
List<Candidate> out = new ArrayList<>(constants.length);
for (CacheStrategyDecl decl : constants) {
    String origin = type.getName() + "#" + ((Enum<?>) decl).name();
    try {
        CacheDefinition def = policies.materialize(decl);   // 策略来自声明，参数来自仓库/内建默认
        def.validate();
        out.add(new Candidate(origin, def));
    } catch (RuntimeException e) {
        throw invalid(where + "：" + origin + " 翻译失败：" + e.getMessage(), e);
    }
}

// ④ 去重 + 冲突检测（跨来源，在 registry 侧统一做）
//    同一 origin 被两条路径发现 → 幂等跳过
//    不同 origin 声明同一 namespace → 启动失败，报双方来源
// ⑤ 注册进 registry → 装配（后续热更走 §6 的 Runtime 替换）
```

去重 / 冲突的具体规则：

```java
Map<String, String> seen = new HashMap<>();          // namespace → origin
for (Candidate candidate : candidates) {
    String namespace = candidate.definition().namespace();
    String previous = seen.putIfAbsent(namespace, candidate.origin());
    if (previous != null && !previous.equals(candidate.origin())) {
        throw invalid("namespace 冲突：" + namespace + " 同时声明于 " + previous
                + " 与 " + candidate.origin());
    }
    // previous == origin ⇒ 同一条声明被重复发现，跳过
}
```

#### 4.2.5.3 六个必须处理的坑

1. **`getEnumConstants()` 对非枚举返回 `null`，不是空数组**；而"枚举没有常量"返回的是长度 0 的数组。
   两者语义不同：前者是错误，后者是合法的空声明。必须分开处理。
2. **用 `isEnum()` 判断，不要用"有 static final 常量"推断**。用户可能写"普通类 + 静态常量"（伪枚举），
   此时 `isEnum()` 为 `false`，必须走 `CacheDeclProvider` 分支。
3. **标识用 `((Enum<?>) c).name()`，绝不用 `toString()`**。`toString` 可被用户覆写（用于展示）；
   `ordinal()` 同样禁止用于排序或持久化（常量顺序变化会漂移）。
4. **`getEnumConstants()` 会触发枚举类初始化**（`static` 块执行）。因此契约要求：
   **`CacheStrategyDecl` 枚举的静态块必须无副作用**（不得在其中注册、读外部配置），
   否则 §4.2.4 的"注册顺序不确定"问题会从后门回来。
5. **Spring 扫描默认不加载类**：`ClassPathScanningCandidateComponentProvider` 基于 ASM 读元数据判候选，
   产出的是 `BeanDefinition` / 类名字符串，必须补一步加载才能取常量：
   ```java
   Class<?> type = ClassUtils.forName(beanDefinition.getBeanClassName(), classLoader);
   ```
   文件清单同理：文件里是字符串，`Class.forName` 之后才能 `getEnumConstants()`。
6. **native image 的注册面差异**（虽排到最后，但影响 API 选择）：`getEnumConstants()` 只需
   **类型可达**；`getMethod("values").invoke(null)` 还需要方法可达。这是选用前者的实质理由之一。

#### 4.2.5.4 转换只发生一次

- 翻译只在**注册时**执行一次（取常量 → 交给仓库补全），结果存进 registry；
- **不要**在枚举里缓存任何派生对象（枚举必须无状态）；
- 热更时**不复用**声明值：生效配置来自仓库。声明值只用于审计与启动比对
  （"运维改过，与代码声明不一致"会打一条 warn，见 §4.5）。

### 4.3 仓库补全与校验（枚举只管策略，参数由仓库给）

枚举只给三个值；把它变成可用的 `CacheDefinition` 是**仓库**的事：

```java
// 仓库内部：materialize(decl) —— 枚举不参与参数决策
CacheDefinition materialize(CacheStrategyDecl decl, NamespacePolicy policy) {
    String namespace = decl.getNamespace();
    LocalStrategy strategy = policy != null ? policy.activeStrategy() : decl.getStrategy();
    LocalCacheSpec local = switch (strategy) {
        case NONE    -> LocalCacheSpec.noLocal(namespace);
        case STATIC  -> LocalCacheSpec.staticCache(namespace, p.maxSize(), p.localTtl(), p.version());
        case DYNAMIC -> LocalCacheSpec.dynamicCache(namespace, p.maxSize(), p.localTtl(),
                                                    p.version(), p.dynamic());
    };
    return CacheDefinition.of(local, p.central(namespace));
}
```

`p` 的取值规则（三层，见 §4.5）：**仓库里该 namespace 的配置 → 都没有则用内建默认**。

**内建默认（唯一一套，不开档位）**：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `maxSize` | `1_000` | 本地条目上限 |
| `localTtl`（非空副本） | `30s` | 本地副本最长存活 |
| 本地空值副本 | `centralNullTtl / N`，`N=10`（可配） | 见下方"本地空值 TTL"；再与 `localTtl` 取小 |
| `version.checkInterval` | `5s` | 版本对账（时间信号量）窗口 |
| `version.jitter` | `100ms`（**绝对值**） | 见下方"抖动语义变更" |
| `version.reconcilePartitions` | `16` | 对账分区数（2 的幂） |
| `version.reconcileBatchSize` | `1_000` | 单次 `HMGET` 的 field 数 |
| `version.retryBackoff` | `200ms`（运行时取 `max(backoff, checkInterval)`） | 对账失败退避 |
| `dynamic.hotThresholdQps` | `100` | 热升阈值，**按 QPS**（原来叫 `localHotThreshold`，语义是窗口内累计次数） |
| `dynamic.window` | `10s` | **滑动**统计窗口长度 |
| `dynamic.hotWindowBuckets` | `10` | 滑动窗口分桶数（每桶 `1s`），越多越精确、内存越高 |
| `dynamic.hotTtl` | **不提供**（与本地非空 TTL 共用） | DYNAMIC 热副本没有独立 TTL |
| `dynamic.hotReportPartitions` | `16` | 热 key 上报分区数 |
| `dynamic.cooldown` | `30s` | 降热冷却 |
| `dynamic.maxHotKeys` / `candidateCapacity` | `1_000` | 热点集合与预选表容量 |
| `centralTtl`（非空） | `30m` | 中央数据存活 |
| `centralNullTtl`（空值） | `30s`；`ZERO` 表示不缓存空值 | 同时决定本地负副本上限 |
| `failOpen` | `true` | 中央不可用时降级为只读本地 |

**本地空值 TTL = `centralNullTtl / N`**（`N` 在仓库里按 namespace 配，默认 `10`）：

- 空值副本本来只是"防穿透的临时标记"，活得比中央还久毫无意义，所以按中央空值 TTL 的
  一个比例缩短，让本地比中央**更早**放下这个标记，避免"中央已能再次回源、本地还在说不存在"；
- 再与 `localTtl` 取小：`localNullTtl = min(localTtl, centralNullTtl / N)`，`N >= 1`；
- `centralNullTtl = ZERO`（不缓存空值）⇒ 负副本两层都不落，`N` 不生效；
- 这是仓库配置项 `localNullTtlDivisor`，**不是**枚举字段，也不给 `LocalCacheSpec` 加组件。

**抖动语义变更（需注意）**：`jitter` 从"比例"（`checkInterval` 的 ±10%）改为**绝对时长**
（`100ms`）。影响：`VersionSpec.jitter` 的类型由 `double` 变为 `Duration`，`jittered()`
由 `base * (1 ± jitter)` 改为 `base ± random(0, jitter)`。好处是不同 `checkInterval` 下抖动幅度一致、
可预测；代价是一次 record 组件类型变更（不兼容）。

**校验**（顺序：先声明后配置，错误信息都能定位到来源）：

1. 声明侧：`namespace` 非空且符合 `CacheKey` 的字符规则、`strategy` 非空、**`description` 非空**；
2. `LocalCacheSpec` 紧凑构造器 + `validate()`：`STATIC/DYNAMIC` 必须有 `version`、`maxSize>0`、
   `ttl>0`、`DYNAMIC` 必须有 `DynamicSpec`；
3. `VersionSpec`：批大小正、分区数为 2 的幂；
4. `CentralCacheSpec`：TTL 为正；
5. **跨字段校验**（集中在仓库）：
   - `DYNAMIC + coordination=GLOBAL` ⇒ 必须存在 `HotKeyCoordinator`；中央实现不支持协调 ⇒ 失败并提示改 `LOCAL`；
   - `centralNullTtl > centralTtl` ⇒ 警告（负副本比真值活得久，通常不是本意）；
   - `localNullTtlDivisor < 1` ⇒ 失败；`centralNullTtl = ZERO` 时该配置被忽略；
   - `version.jitter` 为绝对时长，必须 `0 <= jitter < checkInterval`（否则抖动会吃掉整个窗口）。

### 4.4 启动流程与 fail-fast

```
1. 加载声明：按约定读 `META-INF/services`（+ Spring 注解扫描 + 显式传入）→ List<CacheStrategyDecl>
2. 补全：仓库按 namespace 取配置（缺失则用内建默认）→ `CacheDefinition`（一次性）
3. 冲突检测：同一 namespace 出现多条声明 → 启动失败（报出全部冲突来源）
4. 中央覆盖：读中央策略存储，命中则以中央为准（运维改过的值优先于代码声明）
5. 校验：逐条 validate，任何一条失败 ⇒ 整个启动失败（不静默跳过）
6. 注册：写入注册表，epoch = 中央 epoch 或 1
7. 装配：按注册表构建 Runtime（**懒装配**，见 6.2）并把句柄交给业务
```

日志（沿用既有格式 `[主题]-子过程-具体信息`）：

```
[缓存装配]-初始化-user:detail-策略=STATIC，maxSize=10000，localTtl=PT5M，version(check=1s,partitions=16)，epoch=3，来源=中央
[缓存装配]-初始化-订单快照-策略=NONE，仅中央，epoch=1，来源=声明
[缓存装配]-初始化-冲突-user:detail-声明来源=USER_DETAIL 与 USER_DETAIL_V2，启动中止
```

> 主题必须是**组件名**（`缓存装配` / `策略仓库` / `统一中央缓存`），不得用 namespace 当主题；
> namespace 放在"具体信息"里，如上。

### 4.5 优先级与"兜底"语义

```
生效策略 = 仓库里该 namespace 的配置
        ?? 枚举声明（策略类型）
        ?? 内建默认（参数）
```

- **没有仓库不是错误**：开发者不实现仓库时，枚举声明 + 内建默认即可正常工作，只是不能热更；
- **有仓库但该 namespace 没配**：回落到枚举声明；
- **有仓库且配了**：仓库生效，枚举的 `strategy` 降级为 `declaredStrategy`（审计用）；
- **重启后仍是运维改过的值** —— 这是热更的前提。本设计**不提供 `reset`**：要回到声明值，
  运维自己再改一次（审计留痕比"一键还原"更重要，也避免误操作把线上配置打回代码旧值）；
- 启动时若发现"生效值 ≠ 声明值"，打一条 info/warn：`[缓存装配]-初始化-<ns>-生效策略=DYNAMIC，代码声明=STATIC，来源=仓库`。

### 4.6 设计原则：DYNAMIC = STATIC + 准入判定

`DYNAMIC` 与 `STATIC` 的本质区别只有一件事：**多一套 hotkey 机制判断这个 key 该不该进本地**。
一旦判定"该进"，后续本地缓存所需的一切——版本号对账、本地非空/空值 TTL、`maxSize`、
淘汰机制——**与 `STATIC` 完全共用同一套配置**。

两个直接推论（都已在契约里体现）：

- **没有独立热 TTL**：热副本的 TTL 就是本地非空 TTL（空值走 `centralNullTtl / N`）。
  "热"只影响**准入**，不影响**存活时长**；
- **仓库里 DYNAMIC 不需要第二套容量/过期配置**：`maxSize`、`localTtl` 一份配置，切换策略时
  这些值原样沿用（这也是 §6.4 迁移矩阵里"STATIC → DYNAMIC 只清空副本、不动参数"的依据）。

### 4.7 热升/热降判定：QPS + 滑动时间窗口

**阈值按 QPS 语义**（不是"窗口内累计次数"），统计用**滑动窗口**，而非固定窗口累加——
固定窗口在窗口边界会出现"跨边界两次计数被算成一次突增"和"边界前后重复计算"两类抖动。

#### 4.7.1 两层结构（粗筛 + 精判）

| 层 | 结构 | 作用 | 内存 |
|---|---|---|---|
| 粗筛 | 共享 Count-Min Sketch（现有 `FrequencySketch`，双桶） | 对**海量一次性 key** 也能估出频次，筛出"值得进候选池"的 key | 固定（几 KB） |
| 精判 | **每 key 一个 O(1) 的滑动窗口状态** | 只对候选池 + 热点集合（各 ≤ 1000）做精确 QPS 判定 | `(候选 + 热点) × 每 key 状态` |

精判不能省：CMS 是概率结构，用它直接跟阈值比较会出现"热点抖动"（误差在阈值附近来回跳）。
反过来粗筛也不能省：只靠精判就要为每个访问过的 key 建状态，长尾 key 会撑爆内存。

#### 4.7.2 精判：惰性双桶 + 线性插值（默认，`hotWindowBuckets` 可调）

每个 key 只存三个字段，O(1) 内存、无后台线程、无定时任务：

```java
long bucketStart;     // 当前桶起点
int  current;         // 当前桶计数
int  previous;        // 上一个桶计数
```

访问时（惰性，只在访问路径上）：

```text
elapsedBuckets = (now - bucketStart) / bucketWidth
if      elapsedBuckets == 0  → current++
else if elapsedBuckets == 1  → previous = current; current = 1; bucketStart += width
else                         → previous = 0;       current = 1; bucketStart = now
```

估计值（线性插值，误差上界 = 一个桶）：

```text
ratio     = (now - bucketStart) / bucketWidth          // 0..1
estimated = previous * (1 - ratio) + current           // 相当于"过去 1 个窗口内的访问次数"
qps       = estimated / windowSeconds
升热判定   = qps >= hotThresholdQps
```

- 默认 `window = 10s`、`hotWindowBuckets = 10`（每桶 1s）→ 误差 ≤ 1s，内存每 key 约 16B；
- 想更精确就调大分桶数（`hotWindowBuckets`），内存线性增长但仍是常数级；
- 想更省就让 CMS 承担更多判定，精判只做确认。

#### 4.7.3 为什么不用另外两种常见方案

| 方案 | 问题 |
|---|---|
| 固定窗口累加（现状） | 边界抖动：窗口末尾一小段时间 + 新窗口开头的一小段时间，会被判成"两倍"或"零"，热升热降来回跳 |
| 纯 EWMA（指数衰减） | 内存同样 O(1)，但"阈值"语义变成"衰减后的加权和"，与 QPS 的换算不直观（`freq = qps × halfLife / ln2`），运维看不懂 |
| 每 key 环形数组（k 桶精确） | 最精确，但每 key 一个数组对象（对象头 + k 个 int），2000 个 key × 10 桶的分配与 GC 压力明显高于三字段版本；精度收益在 QPS 判定这种场景里不值 |

#### 4.7.4 热降：对称的 QPS 判定 + 持续性要求

```text
降热判定 = qps < coolThresholdQps 且 连续 N 个检查点都成立（N = demoteWindows）
```

- 单点低于阈值就降热会抖（一两秒没流量就被踢出本地），必须要求**持续**低于；
- 中央侧（`GLOBAL`）同样按 QPS 统计窗口内的访问，阈值 `hotThresholdQps` / `coolThresholdQps`
  都由仓库按 namespace 配；
- `cooldown`（冷却期）保留：降热后一段时间内即使又达标也不许升热，抑制抖动。

#### 4.7.5 连带的参数与实现变更

| 项 | 变化 |
|---|---|
| `DynamicSpec.localHotThreshold`（int，窗口累计次数） | 改名 **`hotThresholdQps`**（int，QPS 语义） |
| `DynamicSpec.window` | 语义变为"滑动窗口长度"，取值不变（10s） |
| 新增 `DynamicSpec.hotWindowBuckets` | 默认 10 |
| `DynamicSpec.hotTtl` | **删除**（见 §4.6） |
| `GlobalSpec.hotThreshold` / `coolThreshold`（long） | 语义改为 QPS，阈值量级从"窗口计数"降到"每秒"，默认值需重算 |
| `FrequencySketch` | 保留作为粗筛；新增 per-key 滑动窗口状态（并入 `hotKeys` / `candidateByPartition` 的值里） |
| 指标 | 控制层新增 `hotQps` 估计值（诊断用），便于运维对照阈值调参 |

> **连带文档**：以上参数改名与语义变更同时要回写到 `LOCAL.md`（热 key 判定一节）、
> `DESIGN.md`（决策表）与 `CentralCacheSpec`/`GlobalSpec` 的默认值；本设计只负责把"仓库里能配什么"定清楚。

## 5. 二、策略仓库 SPI

### 5.1 两种仓库，一个抽象

```java
/** 注册表：进程内唯一权威，热更落点。 */
public interface CacheStrategyRegistry {

    /** 注册一批候选声明（启动期用）。同来源幂等、跨来源同 namespace 冲突即失败。 */
    void registerAll(List<CacheCandidate> candidates);

    /** @return 当前生效定义（含 epoch），未注册返回 empty */
    Optional<CacheStrategyView> find(String namespace);

    /** @return 全部生效定义快照（运维视图用） */
    List<CacheStrategyView> all();

    /** 提交变更：校验 → 构建 → 原子切换 → 广播。失败不改动任何状态。 */
    StrategyChangeResult change(StrategyUpdate update);

    /** @return 若中央 epoch 已前进则返回新定义，否则 empty（访问驱动检查用） */
    Optional<CacheStrategyView> refresh(String namespace);
}

/** 持久化：多节点一致的来源。可选实现（无 Redis 时用 NoopStore 退化为单节点）。 */
public interface CacheStrategyStore {

    Optional<CacheStrategyView> load(String namespace);

    /** 乐观锁写入：仅当 expectedEpoch 匹配时成功。 */
    boolean save(CacheStrategyView view, long expectedEpoch);

    /** @return 全局或按 namespace 的当前 epoch（脏检查用，必须是单次往返） */
    Map<String, Long> epochs(Collection<String> namespaces);
}

public record CacheStrategyView(
        String namespace,
        String description,               // 来自仓库；仓库没配时取枚举声明
        CacheDefinition definition,
        LocalStrategy declaredStrategy,   // 代码声明值（审计与启动比对；不提供 reset）
        long epoch,
        long updatedAtMillis,
        String updatedBy,
        String reason,
        Source source                      // DECLARED / CENTRAL / DEFAULT
        // activeStrategy 即 definition.local().strategy()；declaredStrategy 恒等于枚举里的值
        // 注：没有 reset —— declaredStrategy 只用于审计与启动比对
) { }

/** 每 namespace 的独立配置载体：仓库里存的就是它。 */
public record NamespacePolicy(
        String namespace,
        String description,
        LocalStrategy strategy,           // 生效策略
        long maxSize,
        Duration localTtl,                // 本地非空副本存活（空值副本由 centralNullTtl 决定）
        LocalCacheSpec.VersionSpec version,
        LocalCacheSpec.DynamicSpec dynamic,   // 仅 DYNAMIC 有意义
        Duration centralTtl,              // 中央非空 TTL
        Duration centralNullTtl,          // 中央空值 TTL；ZERO = 不缓存空值
        boolean failOpen,
        long epoch,
        long updatedAtMillis,
        String updatedBy,
        String reason,
        LocalStrategy declaredStrategy    // 枚举声明值，审计用
) { }
```

### 5.2 中央存储布局（Redis）

```
__strategy:<namespace>   HASH
    strategy    STATIC | DYNAMIC | NONE
    maxSize     10000
    localTtlMs  300000
    version     check=1000;partitions=16;batch=1000;backoff=200;jitter=0.1
    dynamic     hotThreshold=...;candidate=...;partitions=16;...
    centralTtlMs     1800000
    centralNullTtlMs 60000        # 空值 TTL；同时决定本地负副本上限
    description      用户详情，读多写少
    declaredStrategy STATIC       # 代码声明值，仅审计
    failOpen     1
    epoch        7
    updatedAt    1758278400000
    updatedBy    ops:zhangsan
    reason       大促期间降低回源压力
```

- 与数据/版本表分开命名空间（`__strategy:` 前缀），`clear(namespace)` **不得**删它；
- 只存"与默认/声明不同"的字段也可以，但为了审计与可读性，建议**存全量**；
- `epoch` 由中央生成（`HINCRBY`），天然单调且多节点一致。

### 5.2.1 每 namespace 都能独立配什么（与"不能配"的边界）

| 能配（仓库里按 namespace） | 不能配（本设计的边界） |
|---|---|
| 本地策略类型 `NONE/STATIC/DYNAMIC` | 命名空间本身（改名等于换缓存） |
| `maxSize`、本地非空 `localTtl` | **本地空值 TTL 的绝对值**（只能通过 `centralNullTtl / N` 间接配） |
| 本地空值 TTL 除数 `N`（`localNullTtlDivisor`，默认 10） | — |
| 版本对账 `version`（间隔/抖动/退避/批大小/分区数） | 档位（本设计不开档位） |
| DYNAMIC 热升热降全套（`localHotThreshold`、`hotTtl`、`cooldown`、`candidateCapacity`、上报分区/批量） | `version.reconcilePartitions` 必须是 2 的幂（校验而非配置自由度） |
| 中央 `centralTtl`（非空）、`centralNullTtl`（空值）、`failOpen` | 中央连接/序列化/key 格式（基础设施，需重新装配） |
| `description`（运维可改写得更清楚） | — |

### 5.3 变更传播：访问驱动检查（不引后台线程）

三个选项：

| 方案 | 实时性 | 代价 |
|---|---|---|
| 推送（pub/sub） | 实时 | 需要长连接与订阅管理，core 不能有；适配层可加 |
| 后台轮询 | 秒级 | **引入调度线程**，与"core 不创建后台线程"的原则冲突 |
| **访问驱动检查** | 下一次访问时生效 | 零线程、零额外资源，与版本对账同构 |

**决策：默认走访问驱动**，机制与 `maybeReconcile` 完全同构：

```
每次读入口（一次 volatile 读 + 比较）：
    if (now - nextStrategyCheckNanos < 0) return;        // 绝大多数请求一次比较就返回
    if (!checking.compareAndSet(false, true)) return;
    try { registry.refresh(namespace); }                  // 一次 HMGET epoch，按需再取全量
    finally { checking.set(false); }
    nextStrategyCheckNanos = now + strategyCheckInterval; // 默认 5s，可配
```

- 检查间隔默认 5s，纯内存比较（无 I/O）——比版本对账更轻；
- 真正的 I/O 只在到点时发生，且**只取 epoch**（`HMGET __strategy:* epoch`），
  只有 epoch 前进才拉全量定义；
- 没有流量就没有变更生效：对缓存来说"没人用"等于"不影响任何人"，可接受；
- 需要"无流量也秒级生效"的场景，由适配层用 Spring `@Scheduled` 主动调 `refresh()`（不属于 core）。

## 6. 三、运行时切换

### 6.1 现状约束（为什么不能只 set 一个字段）

`DefaultTwoLevelCache` 现在的形态：

```java
private final LocalCache<V> local;                          // 实例与 spec 绑定
private final int partitions;                               // 来自 version.reconcilePartitions
private final Map<CacheKey, Integer> keyPartitions;         // 分区号表
private final Map<Integer, Set<CacheKey>> localPartitions;   // 分区索引
private volatile long nextCheckAtNanos;                     // 对账信号量
```

换策略意味着这五样要**一起**换（`NONE→STATIC` 连实例都要新建；`STATIC→DYNAMIC` 要加装饰器；
`reconcilePartitions` 变化要重建索引）。逐个字段 set 会让并发请求看到"新策略 + 旧索引"的中间态。

### 6.2 方案：不可变 `Runtime` + `AtomicReference`

```java
/** 一次策略定义的完整运行时，构造完成后不可变。 */
final class StrategyRuntime<V> {
    final LocalCacheSpec localSpec;
    final LocalCache<V> local;
    final int partitions;
    final Map<CacheKey, Integer> keyPartitions;
    final Map<Integer, Set<CacheKey>> localPartitions;
    volatile long nextCheckAtNanos;
    final long epoch;
    // 构造器完成：建实例 + 注册 onEntryRemoved + 初始化分区索引
}

/** 门面持有的句柄。 */
private final AtomicReference<StrategyRuntime<V>> runtime;

public Lookup<V> lookup(K key) {
    StrategyRuntime<V> r = runtime.get();      // ← 请求级快照，全程只用 r
    ...
}
```

要点：

- **请求级快照**：一次读/写从进入到结束只认一个 `r`，绝不混用两代状态；
- **`set()` 提供发布保证**：新 `Runtime` 的所有字段在构造器里写完，`set` 之后其他线程看到的是
  完整新对象（安全发布，无需额外 volatile 字段）；
- **旧 `Runtime` 不 `close()`**：本地实现（`HashMapLocalCache` 分段 LRU、`CaffeineLocalCache`
  用 `.executor(Runnable::run)` 且无 scheduler）都没有后台线程或外部句柄，直接丢引用交给 GC 即可。
  主动 `close()` 反而会打断在途请求的读。**这是"能无锁切换"的关键前提，必须作为不变量写进文档。**

### 6.3 切换算法

```
change(update):
  1. 取当前 view；epoch 不匹配 expectedEpoch → CONFLICT（带上当前 epoch）
  2. 合并 update 到目标 definition（只允许改本地策略/参数，见 6.9）
  3. 校验：definition.validate() + 跨字段规则（4.3）
  4. 构建新 Runtime（新建本地实例；按需预热；失败则整个操作放弃）
  5. 若配置了 Store：save(view, expectedEpoch)（乐观锁；失败 → CONFLICT）
  6. runtime.set(newRuntime)                    ← 唯一的原子提交点
  7. 记录：日志 + 指标 + 审计（operator / reason / from → to / epoch）
```

- **顺序是"先持久化、后生效"**：持久化失败时本地不切（避免"节点自己变了、其他节点没变"）；
- 若 `Store` 成功而 `set` 之前进程崩溃 → 重启后按中央为准，结果仍是新策略 ✓ 幂等；
- 第 4 步构建失败（类缺失、`DYNAMIC+GLOBAL` 缺 coordinator）⇒ 返回 `REJECTED`，旧 Runtime 原封不动。

### 6.4 状态迁移矩阵（逐条语义）

| 从 → 到 | 本地副本 | 热点/候选/冷却 | 分区索引 | 说明 |
|---|---|---|---|---|
| `NONE → STATIC` | 空 | — | 新建 | 首次读回填；写入立即可落本地 |
| `NONE → DYNAMIC` | 空 | 空（冷启动） | 新建 | 需 `coordinator`；升热前所有读都直连中央 |
| `STATIC → DYNAMIC` | **清空** | 空 | 重建 | 见下方论证 |
| `DYNAMIC → STATIC` | **保留** | 清空 | 重建 | 现成副本都是热点数据，直接转"全量接受" |
| `* → NONE` | **清空** | 清空 | 清空 | 立即停用本地；读直连中央 |
| `STATIC → STATIC`（仅参数） | 保留 | — | `partitions` 变化则重建，否则沿用 | 索引重建成本 O(本地条目数) |
| `DYNAMIC → DYNAMIC`（仅参数） | 保留 | 保留 | 同上 | 阈值变化在下一次访问评估 |

**为什么 `STATIC → DYNAMIC` 要清空**：`DYNAMIC` 的 `accepts()` 只认真实热点
（`hotKeys.containsKey`）。若保留原副本，"读能命中但 `accepts=false`"，写入会被静默丢弃 ——
出现"读得到、写不进"的诡异状态。清空的代价是一次回源小高峰，可用**预热**（上线前主动 `lookup`
关键 key）缓解；这比语义混乱划算。

**为什么 `DYNAMIC → STATIC` 保留**：`STATIC` 接受一切 key，保留副本没有语义冲突，还能避免回源高峰。

`* → NONE` 的"在途请求"：正在跑的请求用的是旧 `Runtime`，会继续写那个已被丢弃的本地实例 ——
**对结果无害**（下次读用新 Runtime，本地为空，直接回中央），不需要额外同步。

### 6.5 并发与单飞

- 单飞 `inflight` 挂在**门面**上（不随 Runtime 重建），所以切换不会让在途 loader 失联；
- loader 执行完的回填报 `runtime.get()`（当前生效的那一代），而不是进入时的 `r` —— 回填的意义是
  让**当前策略**受益。若期间又切了一次，回填可能落在更新的策略上，仍正确；
- `CacheCounters` 也在门面上，**切换不清零**（否则命中率曲线会被打断）；另加
  `strategySwitchCount`、`strategyCheckCount`、`strategyCheckErrorCount`。

### 6.6 失败与回滚

- **不做半应用**：校验、构建、持久化、提交四步，任何一步失败都返回"旧状态不变"；
- 不做自动回滚到上一版（容易在故障时来回抖动）；需要回退就再执行一次 `change`，
  审计里能看到完整链条；
- 中央策略损坏（字段解析失败）⇒ `refresh` 记 `strategyCheckErrorCount` 并按**旧值继续**运行，
  同时 `warnThrottled` 告警；连续失败超过阈值只告警不变更。

### 6.7 幂等与乐观锁

- 目标定义与当前定义**逐字段相等** ⇒ 返回 `NO_CHANGE`，不重建 Runtime（避免无意义的副本清空）；
- `expectedEpoch` 必填：两个运维同时改同一 namespace，后到者拿到 `CONFLICT` + 当前 epoch；
- `epoch` 只由中央递增，客户端不生成。

### 6.8 观测

日志（`warn` 级，审计层）：

```
[统一中央缓存]-切换-user:detail-STATIC → DYNAMIC，maxSize=5000，epoch=8，操作人=ops:zhangsan，原因=大促热点，耗时=12ms
[统一中央缓存]-切换-user:detail-拒绝：DYNAMIC+coordination=GLOBAL 缺少 HotKeyCoordinator，epoch 仍为 8
[策略仓库]-检查-user:detail-epoch 未变化（8），跳过重新装配
```

指标（新增，归入"控制层"，不参与 `hitRate`）：

```java
long strategySwitchCount();       // 成功切换次数
long strategySwitchRejected();    // 被拒次数（校验/持久化/冲突）
long strategyCheckCount();        // 中央 epoch 检查次数
long strategyCheckErrorCount();   // 检查失败次数
long strategyEpoch();             // 当前生效 epoch（gauge 语义，快照里给具体值）
```

### 6.9 变更范围（明确边界）

| 参数 | 允许热更 | 说明 |
|---|---|---|
| `strategy`（NONE/STATIC/DYNAMIC） | ✅ | 本设计核心场景 |
| `maxSize` / `localTtl` / `version` | ✅ | 需要重建 Runtime（索引重建） |
| `dynamic.*`（阈值、冷却、分区） | ✅ | 重建 Runtime，热点集合重置 |
| `centralNullTtl` / `failOpen` | ✅ | 只影响后续写入/降级行为；空值 TTL 同时是本地负副本的上限 |
| `description` | ✅ | 运维可改写得更准确（声明值仍在审计里） |
| **本地空值 TTL** | ❌ | 不提供独立旋钮：= `min(localTtl, centralNullTtl / N)` |
| `localNullTtlDivisor`（N） | ✅ | 改它即可整体调整本地空值副本寿命 |
| `central.ttl` / `ttlJitter` | ⚠️ 允许但需谨慎 | 只影响**后续写入**，已写入的 key 保留旧 TTL；不做 retro 修正 |
| 中央连接 / 序列化 / key 格式 | ❌ | 属于基础设施，必须重新装配并接受数据不兼容 |
| `namespace` 本身 | ❌ | 改名等于换缓存 |

## 7. 四、运维 API

库只提供**动作**，暴露端点由使用方决定（core 不引 web 依赖）：

```java
public interface CacheStrategyAdmin {

    List<CacheStrategyView> list();

    Optional<CacheStrategyView> find(String namespace);

    /**
     * 修改策略。
     *
     * @param update namespace + 目标参数 + expectedEpoch + operator + reason
     * @return 结果（APPLIED / NO_CHANGE / CONFLICT / REJECTED，带 message 与当前 epoch）
     */
    StrategyChangeResult change(StrategyUpdate update);

}

public record StrategyUpdate(
        String namespace,
        Map<String, String> patch,     // 例：strategy=DYNAMIC, maxSize=5000
        long expectedEpoch,
        String operator,
        String reason
) { }
```

**不提供 `reset`**：需要恢复时运维再执行一次 `change`（把值改回去），审计里能看出完整链条。
这样避免"一键还原把线上配置打回代码旧值"这类误操作，也让"谁在什么时候改了什么"始终有记录。

安全：`operator` 只是审计字段，**鉴权由使用方在 HTTP 层做**；文档与启动日志都要提示这一点。

## 8. 五、模块归属与依赖方向

| 模块 | 新增内容 | 依赖 |
|---|---|---|
| `cache4j-core` | `CacheStrategyDecl`、`CacheDefinition`（下沉）、`CacheStrategyRegistry`/`Store` SPI、`StrategyRuntime`、`CacheStrategyLoader`（ServiceLoader）、`CacheStrategyAdmin` | 无新增 |
| `cache4j-local` / `cache4j-local-caffeine` / `cache4j-central` / `cache4j-central-redis` | **不改**（只被装配） | 不变 |
| `cache4j-spring` | `@CacheNamespace` 注解 + 包扫描发现、可选 Actuator 端点 | Spring Framework |
| `cache4j-spring-redis` | `RedisStrategyStore`（HASH 读写 + epoch 自增） | Spring Data Redis |
| `cache4j-starter` | 聚合 + 默认装配（`InMemoryRegistry` + `NoopStore` 或 Redis Store 二选一） | 全部 |

依赖方向不变：`core` 不依赖任何其他模块。

## 9. 六、与现有设计的一致性（必须守住的不变量）

1. **主题 = 组件名**：`缓存装配` / `策略仓库` / `统一中央缓存`，namespace 只出现在具体信息里；
2. **读写日志格式**保持 `[主题]-读|写-[ns#hash]-详情`；切换日志属于审计，用 `-切换-` 子过程；
3. **core 不创建后台线程**：变更检查挂在访问路径上（6.3）；
4. **core 不引第三方**：包扫描放 Spring 层，Redis 存储放 Redis 适配层；
5. **不静默降级**：`DYNAMIC + GLOBAL` 缺 coordinator、中央声明冲突、校验失败一律启动失败或明确 `REJECTED`；
6. **版本对账不变量不变**：`version` 变化时分区索引必须重建，否则"分区号表"与"每分区 key 数"不一致
   （现有实现依赖 `partitions` 固定）；
7. **`clear(namespace)` 不得删 `__strategy:` 前缀**（否则清缓存会把策略一起清掉）；
8. **统计口径**：切换不清零计数器，`strategyEpoch` 作为 gauge 暴露；
9. **本地负副本的存活由中央空值 TTL 按比例派生**：本地层**没有**独立的"空值 TTL"旋钮，
   `localNullTtl = min(localTtl, centralNullTtl / N)`，`N` 由仓库按 namespace 配（默认 10）；
   `centralNullTtl = ZERO` 表示不缓存空值，此时负副本不落本地也不落中央；
10. **DYNAMIC = STATIC + 准入判定**（§4.6）：热副本的 TTL、`maxSize`、淘汰机制、版本对账与
    `STATIC` 共用同一套配置；"热"只决定**该不该进本地**，不决定**活多久**；空值热副本走第 9 条；
11. **枚举只声明策略，不声明参数**：`CacheStrategyDecl` 恒为三个成员
    （`namespace` / `strategy` / `description`），不开档位、不加可选参数；
12. **`description` 非空**：空白即启动失败（运维视角的唯一说明）；
13. **契约是 getter 命名**：`getNamespace()` / `getStrategy()` / `getDescription()`，
   枚举配 Lombok `@Getter` 即可零样板实现（库不依赖 Lombok）。

## 10. 七、测试计划

| 用例 | 断言 |
|---|---|
| 声明发现：ServiceLoader | 枚举被加载、注册，`all()` 可见 |
| 声明发现：Spring 扫描 | 注解枚举被发现，与 ServiceLoader 结果幂等合并 |
| 声明冲突 | 同 namespace 两条声明 → 启动失败，报出全部来源 |
| 校验失败 | `DYNAMIC` 无 `DynamicSpec`、`GLOBAL` 无 coordinator → 启动失败 |
| 中央覆盖 | 中央有值 ⇒ 启动用中央值；枚举值降级为 `declaredStrategy`，并打一条不一致日志 |
| **迁移矩阵** | 6.4 的 7 条逐条验证：副本/热点/索引的期望状态 |
| **切换并发** | 8 线程持续读写 + 切换 100 次，无异常、无"读得到写不进"、索引与副本一致 |
| 幂等 | 同定义 `change` ⇒ `NO_CHANGE`，Runtime 实例不变（可用 identity 断言） |
| 乐观锁 | 两个 `change` 带同一 `expectedEpoch` ⇒ 一个成功一个 `CONFLICT` |
| 持久化失败 | Store 抛异常 ⇒ 本地策略不变，返回 `REJECTED` |
| 检查节流 | 5s 内多次读只发一次 epoch 检查 |
| 中央损坏 | 字段解析失败 ⇒ 继续用旧策略 + 计数 + 告警 |
| 无临时策略 | 变更不会自动过期（随 `reset` 一并取消，需要恢复就再改一次） |
| `clear` 保护 | `clear(namespace)` 后 `__strategy:` 仍在 |
| `description` 非空 | 空白描述 ⇒ 启动失败，报出 `枚举#常量` |
| 无仓库可用 | 不实现仓库时：枚举 + 内建默认即可装配成功，且不报错 |
| 本地空值副本 | `centralNullTtl=ZERO` ⇒ 负副本两层都不落；`centralNullTtl=200ms < localTtl` ⇒ 本地负副本 200ms 后消失 |
| 热升 QPS 判定 | 100 QPS 持续 10s ⇒ 升热；10 QPS 持续 10s ⇒ 不升热；**跨窗口边界**匀速流量不产生"双倍/归零"抖动 |
| 热降持续性 | QPS 掉到阈值以下但未满 `demoteWindows` ⇒ 不降热；连续满足 ⇒ 降热并进入冷却 |
| **native image 冒烟** | 三档各一个最小程序，`native-image` 构建后能加载声明并装配缓存（回填实测的 hints 模板） |

## 11. 八、分期实施

| 期 | 范围 | 产出 |
|---|---|---|
| **P1** | `CacheStrategyDecl` + `CacheDefinition` 下沉 + 声明加载器（文件约定 + `getEnumConstants`）+ `InMemoryRegistry` + `StrategyRuntime` 改造（`DefaultTwoLevelCache` 内部按 Runtime 组织） | 声明式初始化可用；**尚无热更**（Runtime 只有一代） |
| **P2** | `change()` + 迁移矩阵 + 乐观锁 + 审计日志 + 指标 | 单节点热更可用 |
| **P3** | `RedisStrategyStore` + 访问驱动 epoch 检查 + 临时策略 | 多节点一致 |
| **P4** | Spring 扫描 + Actuator 端点 + `cache4j-starter` | 开箱可用 |
| **P9（暂不排期）** | `RuntimeHintsRegistrar` + 模板生成 + native image 冒烟（可选：生成式零反射入口） | GraalVM 可部署（详见 §12） |

**GraalVM 排在所有功能之后，现阶段只做"不给自己挖坑"的三条约束**（不做实现、不做 CI）：

1. **不给库加 `--initialize-at-build-time`**（`CacheLogger` 的绑定探测会被固化，见 §12.6）；
2. **核心不引入运行期 classpath 扫描**，也不引入新的未登记反射用法（新增反射点必须先想清楚能否在 hints 里精确注册）；
3. **声明与装配的元数据保持"静态可枚举"**：类名以字符串常量形式存在（文件清单 / 生成的常量表），
   而不是运行期推导出来的，这样将来补 hints 时是"照抄清单"而不是"重构发现机制"。

> P1 的 Runtime 改造是纯重构（行为不变），可以先独立合入，把风险与热更解耦。

## 12. 九、GraalVM / native image 支持方案

### 12.1 障碍清单（谁的问题、怎么解）

先看清一件事：**本库主体几乎没有反射**。全仓 `src/main` 里与 native image 相关的点只有：

```text
KeyEncoders.java:  java.lang.reflect.Array.get / getLength   ← 不需要注册（Array 反射无 hints）
Cache4jKeyGenerator.java:  java.lang.reflect.Method          ← 框架传入的 Method，本身不需注册
SpringRedisValueStore.java: RedisSerializer<V>               ← 业务提供，库不持有值类型
```

真正的障碍集中在**声明发现**和**第三方依赖**：

| # | 障碍 | 位置 | 责任方 | 解法 |
|---|---|---|---|---|
| 1 | `META-INF/services` 资源**默认不打包**进 native image | 声明发现（§4.2） | 本库 | `hints.resources().registerPattern(...)` |
| 2 | `Class.forName` 的目标被裁掉 | 声明发现 | 本库 | 按静态清单逐类注册反射 |
| 3 | `getEnumConstants()` 需要枚举类可达 | 声明发现 | 本库 | 同上（只需类型可达） |
| 4 | 非枚举 provider 的无参构造器 | 声明发现的兼容分支 | 本库 | `MemberCategory.INVOKE_DECLARED_CONSTRUCTORS` |
| 5 | SLF4J 绑定发现（`ServiceLoader`） | `CacheLogger` | 使用方 | 选有 native 支持的绑定（logback 官方支持）；SLF4J 2.x 自身有 provider hints |
| 6 | 值序列化 | `RedisSerializer<V>`（业务提供） | 使用方 | **本库设计上已规避**：库不持有值类型，无需注册任何业务类型 |
| 7 | Redis 客户端（Lettuce / Netty） | `cache4j-spring-redis` | 使用方 / Boot | 用 Spring Boot 3 的 native 支持（Boot 已提供 Redis + Lettuce + Netty hints） |
| 8 | Caffeine 内部机制 | `cache4j-local-caffeine` | 使用方 | 参考 Caffeine 官方 native 文档（可能要求对部分类做 build-time init）；**本文不写死，待实测** |
| 9 | Spring `@Cacheable` 代理 | `cache4j-spring` | Spring AOT | 无需额外动作（`TwoLevelCacheManager` 是普通 bean，不自造动态代理） |
| 10 | 构建期初始化陷阱 | 全库 | 本库 | **不要**加 `--initialize-at-build-time=com.asialjim.cache4j`，见 12.6 |

### 12.2 库自带的 AOT 能力（Spring 场景，推荐）

`cache4j-spring` 提供 `RuntimeHintsRegistrar`，由 Spring 6 的 AOT 流程自动调用：

```java
public final class Cache4jRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // ① 声明文件本身必须作为资源留住
        hints.resources().registerPattern(
                "META-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl");
        hints.resources().registerPattern(
                "META-INF/services/com.asialjim.cache4j.core.strategy.CacheDeclProvider");

        // ② 清单里出现的每个类都要可达；清单在 AOT 阶段就能读到，所以是"照实注册"而非猜
        for (String className : CacheStrategyLoader.declaredClassNames(classLoader)) {
            hints.reflection().registerType(TypeReference.of(className));
        }
        for (String className : CacheStrategyLoader.providerClassNames(classLoader)) {
            hints.reflection().registerType(TypeReference.of(className),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}
```

通过 `META-INF/spring/aot.factories` 声明（Spring 6 的约定），**用户零配置**。

> 为什么这套 hints 是可靠的：`declaredClassNames()` 读的就是构建期真实存在的清单文件。
> 清单里写了 3 个枚举，就注册 3 个类——不会漏、不会多。

### 12.3 非 Spring 场景：三种交付方式

| 方式 | 用户动作 | 反射 | 适用 |
|---|---|---|---|
| **A. 手抄模板** | 把库生成的 `reflect-config.json` + `resource-config.json` 模板加入 `META-INF/native-image/` | `Class.forName` + `getEnumConstants` | 快速验证；清单稳定的项目 |
| **B. 显式传入** | `options.declEnums(UserCacheDecls.class)` | 同上，但**不需要** resource-config（不读文件） | 不想维护 resources |
| **C. 生成式注册（零反射）** | 调用生成的 `Cache4jDecls.register(registry)` | **无** | 有严格 native 要求、或启动时间敏感 |

A 的模板由库提供生成能力（不是让人手写）：

```text
# reflect-config.json（示例：清单里有 2 个枚举）
[ { "name": "com.acme.cache.UserCacheDecls",  "allDeclaredConstructors": false },
  { "name": "com.acme.cache.OrderCacheDecls", "allDeclaredConstructors": false } ]

# resource-config.json
{ "resources": { "includes": [
    { "pattern": "\\QMETA-INF/services/com.asialjim.cache4j.core.strategy.CacheStrategyDecl\\E" }
] } }
```

C 的实现方式二选一（P5 再定，见决策 S9）：

- **Spring AOT**：实现 `BeanFactoryInitializationAotProcessor` 生成 `Cache4jDecls`，在
  `ApplicationContextInitializer` 风格入口里调用（Boot 3 原生支持）；
- **APT**：`@CacheNamespace` 在编译期生成 `Cache4jDecls`，用户在自己的启动代码里调一行。

两者生成的都是"编译期静态引用 `XxxDecls.values()`"，所以**完全没有反射**。

### 12.4 使用方必须配合的四件事

1. **日志绑定**：选有 native 支持的 SLF4J 绑定（logback ≥ 1.4 官方支持）；`slf4j-simple` 只适合测试；
2. **值序列化**：库不碰字节，`RedisSerializer<V>` 由业务提供 → 业务自己注册类型
   （Jackson 用 `@RegisterReflectionForBinding` 或 `@JsonTypeInfo`；**不要**用 JDK 序列化，
   native image 下它对业务类基本不可用）；
3. **Redis 客户端**：跟随所选客户端的 native 支持（Boot 3 用户直接继承 Boot 的 hints）；
4. **Caffeine**：按 Caffeine 官方 native 文档配置（若用 `cache4j-local` 的 HashMap 实现则无此问题）。

### 12.5 验证方式（不做就不写结论）

| 验证项 | 内容 |
|---|---|
| 冒烟 A（Spring） | `native-image` 构建 Spring Boot 3 示例：加载声明 → 一次 `put`/`lookup` → 触发一次对账 → 断言命中统计 |
| 冒烟 B（无 Spring，模板） | 同上，但用 `reflect-config.json` + `resource-config.json` |
| 冒烟 C（零反射） | 同上，但走 `Cache4jDecls.register(...)`，构建时不加任何 hints |
| CI | 可选加 GraalVM job（构建 + 跑冒烟 A），耗时长，可只在 release 分支执行 |

实测结果回填 §4.2.3 的模板（`getEnumConstants()` 的最小注册项随 GraalVM 版本变化）。

### 12.6 两个必须写进文档的告诫

1. **不要给本库加 `--initialize-at-build-time`**：`CacheLogger` 首次调用会做 SLF4J 绑定探测，
   `HashMapLocalCache` 等实现没有静态状态但 `CacheLogger.DIAGNOSED` 是静态的——构建期初始化的
   静态状态会在运行期沿用，可能导致日志绑定被固化到错误实现。保持默认的**运行期初始化**。
2. **不承诺"零配置"**：除第 C 种生成式方式外，native image 都需要 hints。把"需要几行配置"
   说清楚，比说"支持 GraalVM"更有用。

### 12.7 明确不承诺的范围

- ❌ 不支持运行期 classpath 扫描（native image 没有 classpath 目录结构）；
- ❌ 不支持 JDK 序列化作为值编码；
- ⚠️ 不保证任意 GraalVM 版本，按实测过的版本给模板；
- ⚠️ 第三方（Lettuce / Netty / Caffeine / 日志绑定）的行为由各自项目负责。

### 12.8 分期

GraalVM 支持归入 **P5**（P1–P4 之后）：先有声明式初始化与热更，再补 AOT 与 native 验证。
理由：native image 是部署形态，不是功能；而且 hints 的内容依赖 P1 的加载器实现才对得上。

## 13. 十、待决策（需要拍板）

| # | 问题 | 备选 | 建议 |
|---|---|---|---|
| S1 | 变更传播机制 | 访问驱动 / 后台轮询 / pub-sub | **访问驱动**（零线程，与对账同构）；pub-sub 留给适配层加速 |
| S2 | `STATIC → DYNAMIC` 是否清空本地副本 | 清空 / 转成热点 | **清空**（否则"读得到写不进"）；配预热缓解 |
| S13 | 枚举契约的成员数 | 三个必需 / 三必需 + 可选档位 / 三必需 + 参数默认方法 | **三个必需**（`namespace`/`strategy`/`description`），参数全归仓库 |
| S14 | 是否开档位（SMALL/STANDARD/LARGE） | 开 / 不开 | **不开**：未配仓库时用唯一一套内建默认，减小心智负担 |
| S15 | 是否提供 `reset` | 提供 / 不提供 | **不提供**：要恢复就再 `change` 一次，审计留痕更重要；临时策略一并取消 |
| S16 | 本地空值副本的存活 | 独立旋钮 / 由中央 `nullTtl` 派生 | **由中央 `nullTtl` 按 `1/N` 派生**（`N` 仓库可配，默认 10），再与 `localTtl` 取小；`LocalCacheSpec` 不加字段 |
| S17 | 枚举契约的命名风格 | 裸方法（`namespace()`）/ getter（`getNamespace()`） | **getter**，配合 Lombok `@Getter` 让"三个字段 + 一个注解"即满足契约 |
| S18 | `version.jitter` 的语义 | 比例（±N%）/ 绝对时长 | **绝对时长**（默认 `100ms`），不同 `checkInterval` 下抖动幅度一致；代价是 `VersionSpec` 组件类型变更 |
| S19 | DYNAMIC 是否保留独立热 TTL | 保留 / 取消 | **取消**：DYNAMIC = STATIC + 准入判定，判定通过后本地配置与 STATIC 完全一致 |
| S20 | 热升/热降阈值的语义 | 窗口内累计次数 / **QPS** | **QPS**（`hotThresholdQps`），运维可直接对照监控里的 QPS |
| S21 | 统计方式 | 固定窗口 / 滑动窗口 / EWMA | **滑动窗口**：CMS 粗筛 + 每 key 惰性双桶线性插值（`hotWindowBuckets` 可调，默认 10），误差 ≤ 1 桶且无后台线程 |
| S3 | `DYNAMIC → STATIC` 是否保留副本 | 保留 / 清空 | **保留**（无冲突且避免回源高峰） |
| S4 | 声明发现方式 | 文件约定（类 ServiceLoader 但不实例化枚举）/ 自研包扫描 / 注解自注册 | **文件约定为主 + Spring 扫描为辅 + 显式传入兜底**；core 不自研包扫描 |
| S5 | 多节点一致性是否 P1 必需 | 是 / 否（先单节点） | 单节点先上（P1–P2），P3 补中央存储 |
| S6 | 是否允许热更中央 TTL | 允许 / 禁止 | **允许但标注"只影响后续写入"**；连接类参数禁止 |
| S7 | 临时策略（`ttlOfChange`） | 做 / 不做 | **不做**（与 `reset` 一起取消：没有"还原目标"就没有过期语义，需要恢复就再改一次） |
| S8 | `CacheDefinition` 是否从 `cache4j-spring` 下沉到 `core.strategy` | 下沉 / 保留双份 | **下沉**，Spring 侧保留同名兼容别名 |
| S9 | 是否提供「真零反射」的生成式入口（APT / AOT 生成 `register(...)`） | 提供 / 不提供 | 先不提供（P1–P3 用文件清单 + 库自带 hints），等有明确 native image 需求再加 |
| S10 | GraalVM 支持的交付深度 | 只出 hints + 模板 / 再加 CI 冒烟 / 再加生成式入口 | **hints + 模板 + 一次实测冒烟**；CI job 与生成式入口按需再加 |
| S11 | 自注册的定位 | 主路径（静态块）/ 降级为可选糖 / 完全不做 | **降级为可选糖**（待定池 + `drain` 一次），主路径用显式 `install`，P2 由 APT 生成清单接管 |
| S12 | 是否引入 `cache4j-processor`（APT） | 引入 / 只用文件清单 | **P2 引入**（用户才能"只写枚举"），但保持可选依赖：不加也能手写一行文件 |
