package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.log.CacheLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认仓库实现：声明登记 + 三层解析（数据源 → 声明 → 内建默认）。
 *
 * <p>数据源可选：为 {@code null} 时退化为"声明 + 内建默认"（不启用仓库的形态）。</p>
 *
 * <p>线程模型：登记与解析发生在启动期（实例锁串行化）；解析完成后 {@link #find}/{@link #all}
 * 读不可变快照，无锁。</p>
 *
 * @author Asial Jim
 */
public final class DefaultCacheStrategyRepository implements CacheStrategyRepository, CacheStrategyAdmin {

    private static final CacheLogger LOG = CacheLogger.get(DefaultCacheStrategyRepository.class);

    private final CacheStrategyDataSource dataSource;

    private final Map<String, DeclEntry> declarations = new LinkedHashMap<>();

    /**
     * 生效配置快照：变更用 CAS 替换，读路径无锁。
     */
    private final AtomicReference<Map<String, NamespacePolicy>> snapshot =
            new AtomicReference<>(Map.of());

    /**
     * 按 namespace 的变更锁：同一 namespace 串行、不同 namespace 并行。
     *
     * <p>不能用一个类级锁——变更里含数据源 I/O，一个慢变更会阻塞所有 namespace 的变更。</p>
     */
    private final ConcurrentHashMap<String, Object> changeLocks = new ConcurrentHashMap<>();

    /**
     * 幂等表：{@code requestId → 首次结果}。作用域仅本进程（管理服务多实例时由乐观锁兜底冲突）。
     */
    private final Map<String, IdempotentEntry> idempotency = new ConcurrentHashMap<>();

    /**
     * 幂等结果保留时长（超过即清理）。
     */
    private static final long IDEMPOTENCY_TTL_NANOS = Duration.ofMinutes(10).toNanos();

    /**
     * 幂等清理的触发计数（每 64 次变更扫一次）。
     */
    private final java.util.concurrent.atomic.AtomicLong idempotencySweepCounter =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * @param dataSource 策略数据源；{@code null} 表示不启用仓库（只用声明 + 内建默认）
     */
    public DefaultCacheStrategyRepository(CacheStrategyDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized void declare(CacheStrategyDecl decl, String origin) {
        String namespace = decl.getNamespace();
        DeclEntry previous = declarations.putIfAbsent(namespace, new DeclEntry(decl, origin));
        if (previous == null) {
            return;
        }
        if (previous.origin().equals(origin)) {
            LOG.debug(CacheLogger.Subject.ASSEMBLY, "声明", origin + " 重复登记，已忽略");
            return;
        }
        throw new CacheException(CacheResCode.INVALID_SPEC,
                "namespace 冲突：" + namespace + " 同时声明于 " + previous.origin() + " 与 " + origin);
    }

    @Override
    public synchronized List<String> bootstrap() {
        Map<String, NamespacePolicy> resolved = new LinkedHashMap<>(declarations.size());
        for (Map.Entry<String, DeclEntry> entry : declarations.entrySet()) {
            String namespace = entry.getKey();
            NamespacePolicy policy = resolve(namespace, entry.getValue());
            resolved.put(namespace, policy);
            logResolved(policy);
        }
        this.snapshot.set(Map.copyOf(resolved));
        LOG.debug(CacheLogger.Subject.ASSEMBLY, "初始化",
                "解析完成，共 " + resolved.size() + " 个 namespace，数据源="
                        + (dataSource == null ? "未启用" : dataSource.getClass().getSimpleName()));
        return new ArrayList<>(resolved.keySet());
    }

    @Override
    public PolicyLookup find(String namespace) {
        NamespacePolicy policy = snapshot.get().get(namespace);
        return policy == null ? new PolicyLookup.Absent() : new PolicyLookup.Found(policy);
    }

    @Override
    public List<NamespacePolicy> all() {
        return new ArrayList<>(snapshot.get().values());
    }

    // ------------------------------------------------------------------ 管理面

    @Override
    public Page<StrategyView> page(String keyword, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        if (dataSource == null) {
            // 未启用数据源：只有声明（数量与代码里的枚举常量同量级，内存分页天然有界）
            String lowered = keyword == null ? "" : keyword.trim().toLowerCase();
            List<StrategyView> all = views().stream()
                    .filter(view -> lowered.isEmpty()
                            || view.namespace().toLowerCase().contains(lowered)
                            || (view.description() != null && view.description().toLowerCase().contains(lowered)))
                    .toList();
            int from = Math.min(all.size(), safePage * safeSize);
            int to = Math.min(all.size(), from + safeSize);
            return new Page<>(all.subList(from, to), safePage, safeSize, all.size());
        }
        // 启用数据源：过滤与分页交给数据源（存储侧），不把全量记录拉回内存
        Page<StoredPolicy> stored = dataSource.query(keyword, safePage, safeSize);
        List<StrategyView> views = new ArrayList<>(stored.items().size());
        for (StoredPolicy policy : stored.items()) {
            views.add(viewOfStored(policy));
        }
        return new Page<>(views, stored.page(), stored.size(), stored.total());
    }

    @Override
    public Optional<StrategyView> view(String namespace) {
        NamespacePolicy policy = snapshot.get().get(namespace);
        return policy == null ? Optional.empty() : Optional.of(viewOf(policy));
    }

    @Override
    public ChangeResult change(StrategyChange change) {
        String invalid = validateChangeRequest(change);
        if (invalid != null) {
            return new ChangeResult(ChangeResult.Outcome.REJECTED, change.namespace(), -1L,
                    null, invalid, change.requestId());
        }
        sweepIdempotencyOccasionally();
        IdempotentEntry previous = idempotency.get(change.requestId());
        if (previous != null) {
            LOG.debug(CacheLogger.Subject.STRATEGY_REPO, "变更",
                    "requestId 重复，返回首次结果：requestId=" + change.requestId()
                            + "，outcome=" + previous.result().outcome());
            return previous.result();
        }
        // 同一 namespace 串行、不同 namespace 并行：变更里含数据源 I/O，不能用类级锁
        String namespace = change.namespace();
        Object lock = changeLocks.computeIfAbsent(namespace, ignored -> new Object());
        ChangeResult result;
        synchronized (lock) {
            // 锁内再查一次幂等：两个并发请求带同一 requestId 时只有一个真正执行
            IdempotentEntry raced = idempotency.get(change.requestId());
            if (raced != null) {
                return raced.result();
            }
            result = doChange(change);
            idempotency.put(change.requestId(), new IdempotentEntry(result, System.nanoTime()));
        }
        return result;
    }

    /**
     * 幂等表清理：超过 TTL 的条目移除（按变更次数触发，避免每次变更都全表扫描）。
     */
    private void sweepIdempotencyOccasionally() {
        if ((idempotencySweepCounter.incrementAndGet() & 0x3FL) != 0L) {
            return;
        }
        long deadline = System.nanoTime() - IDEMPOTENCY_TTL_NANOS;
        idempotency.entrySet().removeIf(entry -> entry.getValue().atNanos() < deadline);
    }

    /**
     * 幂等结果条目（带写入时刻，供过期清理）。
     *
     * @param result  首次结果
     * @param atNanos 写入时刻
     */
    private record IdempotentEntry(ChangeResult result, long atNanos) {
    }

    /**
     * 变更主体：逐字段比较 → 乐观锁 → 试装配 → 持久化 → 原子提交 → 审计。
     *
     * @param change 请求
     * @return 结果
     */
    private ChangeResult doChange(StrategyChange change) {
        NamespacePolicy target = change.policy();
        String namespace = target.namespace();
        NamespacePolicy current = snapshot.get().get(namespace);
        DeclEntry decl = declarations.get(namespace);

        // ① namespace 必须已存在（新增缓存请先声明再部署，而不是在线凭空造）
        if (current == null && decl == null) {
            return reject(change, "namespace 不存在：" + namespace + "（请先在代码里声明该缓存）");
        }

        // ② 逐字段比较
        if (current != null && samePolicy(current, target)) {
            return new ChangeResult(ChangeResult.Outcome.NO_CHANGE, namespace,
                    current.configVersion(), current, "目标配置与当前完全一致，未做任何写入",
                    change.requestId());
        }

        // ③ 乐观锁
        long expected = target.configVersion();
        if (current != null && expected != current.configVersion()) {
            return new ChangeResult(ChangeResult.Outcome.CONFLICT, namespace, current.configVersion(),
                    current, "配置版本不匹配：期望 " + expected + "，当前 " + current.configVersion()
                    + "（请重新读取后再提交）", change.requestId());
        }

        // ④ 试装配：先确认这套配置在本节点能装起来，再落库
        try {
            target.toDefinition().validate();
        } catch (RuntimeException e) {
            return reject(change, "配置校验失败：" + e.getMessage());
        }

        // ⑤ 持久化（数据源可选：未启用仓库时不允许变更）
        if (dataSource == null) {
            return reject(change, "策略数据源未启用：没有持久化目标，变更无法生效");
        }
        StoredPolicy stored = toStored(target, change);
        long newVersion;
        try {
            if (current == null) {
                if (!dataSource.register(stored)) {
                    return new ChangeResult(ChangeResult.Outcome.CONFLICT, namespace, -1L, null,
                            "该 namespace 的策略已被其他请求创建（register 已存在）", change.requestId());
                }
                newVersion = Math.max(1L, stored.configVersion());
            } else {
                OptionalLong updated = dataSource.update(stored, expected);
                if (updated.isEmpty()) {
                    return new ChangeResult(ChangeResult.Outcome.CONFLICT, namespace,
                            current.configVersion(), current,
                            "数据源拒绝更新：版本已被其他请求推进", change.requestId());
                }
                newVersion = updated.getAsLong();
            }
        } catch (RuntimeException e) {
            LOG.warnThrottled(CacheLogger.Subject.STRATEGY_REPO, "变更", () ->
                    "持久化失败，生效值不变：namespace=" + namespace + "，原因=" + e.getMessage());
            return reject(change, "持久化失败：" + e.getMessage());
        }

        // ⑥ 原子提交：换快照（写穿缓存由装配层负责，见 STRATEGY.md §4.3）
        NamespacePolicy committed = new NamespacePolicy(
                namespace, target.description(), target.strategy(), target.declaredStrategy(),
                target.maxSize(), target.localTtl(), target.localNullTtlDivisor(),
                target.version(), target.dynamic(),
                target.centralTtl(), target.centralNullTtl(), target.failOpen(),
                newVersion, change.requestedAtMillis(), change.operator(), change.reason(),
                NamespacePolicy.Source.REPOSITORY);
        // CAS 替换快照：不持类级锁也能保证"读到的永远是完整的一份"
        while (true) {
            Map<String, NamespacePolicy> currentSnapshot = snapshot.get();
            Map<String, NamespacePolicy> next = new LinkedHashMap<>(currentSnapshot);
            next.put(namespace, committed);
            if (snapshot.compareAndSet(currentSnapshot, Map.copyOf(next))) {
                break;
            }
        }

        // ⑦ 审计（APPLIED 用 info）
        LOG.info(CacheLogger.Subject.STRATEGY_REPO, "变更", "APPLIED，namespace=" + namespace
                + "，configVersion " + expected + "→" + newVersion
                + "，strategy=" + (current == null ? "（新增）" : current.strategy() + "→" + target.strategy())
                + "，maxSize=" + target.maxSize()
                + "，操作人=" + change.operator() + "，原因=" + change.reason()
                + "，requestId=" + change.requestId());
        return new ChangeResult(ChangeResult.Outcome.APPLIED, namespace, newVersion, committed,
                "已生效", change.requestId());
    }

    /**
     * @param change 请求
     * @return 校验失败原因；通过返回 {@code null}
     */
    private String validateChangeRequest(StrategyChange change) {
        if (change == null) {
            return "变更请求不能为空";
        }
        if (isBlank(change.requestId())) {
            return "requestId 不能为空（幂等键必填）";
        }
        if (isBlank(change.operator())) {
            return "operator 不能为空（审计必填）";
        }
        if (isBlank(change.reason())) {
            return "reason 不能为空（审计必填）";
        }
        String textIssue = checkAuditText("operator", change.operator());
        if (textIssue != null) {
            return textIssue;
        }
        textIssue = checkAuditText("reason", change.reason());
        if (textIssue != null) {
            return textIssue;
        }
        if (change.policy() == null) {
            return "policy 不能为空";
        }
        return null;
    }

    private String checkAuditText(String field, String value) {
        if (value.length() > 128) {
            return field + " 长度不能超过 128，实际 " + value.length();
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return field + " 不允许包含控制字符（会污染日志）";
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChangeResult reject(StrategyChange change, String message) {
        LOG.warn(CacheLogger.Subject.STRATEGY_REPO, "变更",
                "REJECTED，namespace=" + change.namespace() + "，操作人=" + change.operator()
                        + "，原因=" + message + "，requestId=" + change.requestId());
        return new ChangeResult(ChangeResult.Outcome.REJECTED, change.namespace(), -1L,
                snapshot.get().get(change.namespace()), message, change.requestId());
    }

    /**
     * 逐字段比较（忽略版本号与审计字段）。
     *
     * @param a 当前
     * @param b 目标
     * @return 是否完全一致
     */
    private static boolean samePolicy(NamespacePolicy a, NamespacePolicy b) {
        return a.strategy() == b.strategy()
                && a.maxSize() == b.maxSize()
                && a.localNullTtlDivisor() == b.localNullTtlDivisor()
                && Objects.equals(a.description(), b.description())
                && Objects.equals(a.localTtl(), b.localTtl())
                && Objects.equals(a.version(), b.version())
                && Objects.equals(a.dynamic(), b.dynamic())
                && Objects.equals(a.centralTtl(), b.centralTtl())
                && Objects.equals(a.centralNullTtl(), b.centralNullTtl())
                && a.failOpen() == b.failOpen();
    }

    private static StoredPolicy toStored(NamespacePolicy policy, StrategyChange change) {
        return new StoredPolicy(
                policy.namespace(), policy.description(), policy.strategy(),
                policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                policy.version(), policy.dynamic(),
                policy.centralTtl(), policy.centralNullTtl(), policy.failOpen(),
                policy.configVersion(), change.requestedAtMillis(), change.operator(), change.reason());
    }

    private List<StrategyView> views() {
        Map<String, NamespacePolicy> current = snapshot.get();
        List<StrategyView> views = new ArrayList<>(current.size());
        current.values().forEach(policy -> views.add(viewOf(policy)));
        return views;
    }

    /**
     * 数据源记录 → 运维视图（分页查询用）。
     *
     * <p>代码里没声明的记录标 {@code orphan=true}（数据源有、声明没有，**不做预装配**，
     * 但运维视图里要能看见并清理）；有声明则按三层解析补齐缺省字段。</p>
     *
     * @param stored 数据源记录
     * @return 视图
     */
    private StrategyView viewOfStored(StoredPolicy stored) {
        DeclEntry decl = declarations.get(stored.namespace());
        boolean casSupported = dataSource == null || dataSource.supportsCas();
        if (decl == null) {
            NamespacePolicy policy;
            try {
                policy = PolicyResolver.fromStored(null, stored);
            } catch (RuntimeException e) {
                // 记录损坏：不自动修复，标 broken 并让其余字段取内建默认
                LOG.warn(CacheLogger.Subject.STRATEGY_REPO, "查询",
                        "记录解析失败，已标记 broken：namespace=" + stored.namespace()
                                + "，原因=" + e.getMessage());
                return null;
            }
            return StrategyView.of(policy, null, casSupported).withOrphan(true);
        }
        NamespacePolicy policy = PolicyResolver.fromStored(decl.decl(), stored);
        return StrategyView.of(policy, decl.decl().getDescription(), casSupported);
    }

    private StrategyView viewOf(NamespacePolicy policy) {
        DeclEntry decl = declarations.get(policy.namespace());
        String declaredDescription = decl == null ? null : decl.decl().getDescription();
        boolean casSupported = dataSource == null || dataSource.supportsCas();
        return StrategyView.of(policy, declaredDescription, casSupported);
    }

    /**
     * 三层解析：数据源 → 声明 → 内建默认。
     *
     * @param namespace 命名空间
     * @param entry     声明
     * @return 生效配置
     */
    private NamespacePolicy resolve(String namespace, DeclEntry entry) {
        CacheStrategyDecl decl = entry.decl();
        if (dataSource == null) {
            return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
        }
        StoredPolicy stored;
        try {
            Optional<StoredPolicy> loaded = dataSource.load(namespace);
            if (loaded.isEmpty()) {
                LOG.debug(CacheLogger.Subject.ASSEMBLY, "初始化",
                        namespace + " 数据源无记录，用声明 + 内建默认");
                return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
            }
            stored = loaded.get();
        } catch (RuntimeException e) {
            // 数据源不可达：不得让业务起不来（除非显式 failClosed，本实现不提供）
            LOG.warnThrottled(CacheLogger.Subject.ASSEMBLY, "初始化", () ->
                    "策略数据源读取失败，namespace=" + namespace + "，按声明 + 内建默认继续，原因=" + e.getMessage());
            return fromDeclaration(decl, NamespacePolicy.Source.DECLARED);
        }
        return fromStored(decl, stored);
    }

    private NamespacePolicy fromDeclaration(CacheStrategyDecl decl, NamespacePolicy.Source ignored) {
        return PolicyResolver.fromDeclaration(decl);
    }

    /**
     * 数据源记录 + 声明 → 生效配置（逻辑集中在 {@link PolicyResolver}，避免两处规则漂移）。
     *
     * @param decl   声明
     * @param stored 数据源记录
     * @return 生效配置
     */
    private NamespacePolicy fromStored(CacheStrategyDecl decl, StoredPolicy stored) {
        return PolicyResolver.fromStored(decl, stored);
    }

    private void logResolved(NamespacePolicy policy) {
        String detail = "namespace=" + policy.namespace()
                + "，strategy=" + policy.strategy()
                + "（声明=" + policy.declaredStrategy() + "）"
                + "，maxSize=" + policy.maxSize()
                + "，localTtl=" + policy.localTtl()
                + "，centralTtl=" + policy.centralTtl()
                + "，centralNullTtl=" + policy.centralNullTtl()
                + "，configVersion=" + policy.configVersion()
                + "，来源=" + policy.source();
        if (policy.consistentWithDeclared()) {
            LOG.info(CacheLogger.Subject.ASSEMBLY, "初始化", detail);
        } else {
            // 声明与生效不一致不告警（业务允许），但信息级留痕便于排障
            LOG.info(CacheLogger.Subject.ASSEMBLY, "初始化",
                    detail + "，注意生效策略与代码声明不同（接受，无需处理）");
        }
    }

    /**
     * 一条已登记的声明。
     *
     * @param decl   声明
     * @param origin 来源
     */
    private record DeclEntry(CacheStrategyDecl decl, String origin) {
    }
}
