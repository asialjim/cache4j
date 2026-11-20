package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理面变更状态机：幂等、校验、逐字段比较、乐观锁、持久化、审计。
 *
 * @author Asial Jim
 */
class CacheStrategyChangeTest {

    private CountingDataSource dataSource;

    private DefaultCacheStrategyRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new CountingDataSource();
        repository = new DefaultCacheStrategyRepository(dataSource);
        repository.declare(Decls.USER_DETAIL, "测试声明");
        repository.bootstrap();
    }

    @Test
    @DisplayName("正常变更：写入数据源、快照更新、版本前进")
    void appliesChange() {
        NamespacePolicy current = repository.find("user:detail").policyOrNull();
        assertEquals(LocalStrategy.STATIC, current.strategy());

        int writesBefore = dataSource.registerCalls + dataSource.updateCalls;
        NamespacePolicy target = withMaxSize(current, 5_000L);
        ChangeResult result = repository.change(request("req-1", target));

        assertEquals(ChangeResult.Outcome.APPLIED, result.outcome(), result.message());
        assertTrue(result.configVersion() > current.configVersion());
        assertEquals(5_000L, repository.find("user:detail").policyOrNull().maxSize());
        assertEquals(writesBefore + 1, dataSource.registerCalls + dataSource.updateCalls,
                "变更应当恰好写一次数据源");
        assertEquals("ops:zhangsan", result.current().updatedBy());
        assertEquals("扩容量", result.current().reason());
    }

    @Test
    @DisplayName("逐字段相同：NO_CHANGE，不写数据源")
    void noChangeWritesNothing() {
        int writesBefore = dataSource.updateCalls + dataSource.registerCalls;
        NamespacePolicy current = repository.find("user:detail").policyOrNull();
        ChangeResult result = repository.change(request("req-2", current));

        assertEquals(ChangeResult.Outcome.NO_CHANGE, result.outcome(), result.message());
        assertEquals(writesBefore, dataSource.updateCalls + dataSource.registerCalls,
                "逐字段相同的变更不应写数据源");
        assertEquals(current.configVersion(), result.configVersion());
    }

    @Test
    @DisplayName("版本不匹配：CONFLICT，返回当前版本且不写数据源")
    void conflictsOnStaleVersion() {
        NamespacePolicy current = repository.find("user:detail").policyOrNull();
        // 先做一次真实变更，把版本推进
        repository.change(request("req-3", withMaxSize(current, 2_000L)));
        long staleVersion = current.configVersion();

        // 用旧版本再提交
        NamespacePolicy target = withMaxSize(repository.find("user:detail").policyOrNull(), 3_000L);
        NamespacePolicy stale = withVersion(target, staleVersion);
        ChangeResult result = repository.change(request("req-4", stale));

        assertEquals(ChangeResult.Outcome.CONFLICT, result.outcome(), result.message());
        assertEquals(1, dataSource.updateCalls, "冲突时不应再写数据源");
        assertEquals(2_000L, repository.find("user:detail").policyOrNull().maxSize(), "生效值不变");
    }

    @Test
    @DisplayName("校验失败：REJECTED，且不写数据源")
    void rejectsInvalidRequest() {
        int writesBefore = dataSource.updateCalls + dataSource.registerCalls;
        NamespacePolicy current = repository.find("user:detail").policyOrNull();

        assertEquals(ChangeResult.Outcome.REJECTED,
                repository.change(new StrategyChange("req-5", "  ", "原因", 0L, current)).outcome());
        assertEquals(ChangeResult.Outcome.REJECTED,
                repository.change(new StrategyChange("req-6", "ops", "", 0L, current)).outcome());
        assertEquals(ChangeResult.Outcome.REJECTED,
                repository.change(new StrategyChange("req-7", "ops", "原因", 0L, null)).outcome());
        assertEquals(writesBefore, dataSource.updateCalls + dataSource.registerCalls,
                "被拒绝的变更不应写数据源");
    }

    @Test
    @DisplayName("operator / reason 含控制字符或超长：REJECTED（防日志注入）")
    void rejectsControlCharsInAuditText() {
        NamespacePolicy current = repository.find("user:detail").policyOrNull();

        ChangeResult control = repository.change(
                new StrategyChange("req-8", "ops", "第一行\n伪造", 0L, withMaxSize(current, 9_000L)));
        assertEquals(ChangeResult.Outcome.REJECTED, control.outcome());
        assertTrue(control.message().contains("控制字符"), control.message());

        ChangeResult tooLong = repository.change(
                new StrategyChange("req-9", "ops", "x".repeat(129), 0L, withMaxSize(current, 9_000L)));
        assertEquals(ChangeResult.Outcome.REJECTED, tooLong.outcome());
    }

    @Test
    @DisplayName("namespace 不存在：REJECTED（新增缓存要先声明再部署）")
    void rejectsUnknownNamespace() {
        NamespacePolicy unknown = withNamespace(
                repository.find("user:detail").policyOrNull(), "nobody:declared");
        ChangeResult result = repository.change(request("req-10", unknown));

        assertEquals(ChangeResult.Outcome.REJECTED, result.outcome());
        assertTrue(result.message().contains("不存在"), result.message());
    }

    @Test
    @DisplayName("同一 requestId 重复提交：返回首次结果，只写一次数据源")
    void idempotentByRequestId() {
        NamespacePolicy current = repository.find("user:detail").policyOrNull();
        NamespacePolicy target = withMaxSize(current, 7_000L);

        ChangeResult first = repository.change(request("req-11", target));
        ChangeResult second = repository.change(request("req-11", target));

        assertEquals(ChangeResult.Outcome.APPLIED, first.outcome());
        assertEquals(first, second, "重复提交必须返回首次结果");
        assertEquals(1, dataSource.updateCalls, "数据源只应被写一次");
    }

    @Test
    @DisplayName("未启用数据源：变更被拒（没有持久化目标）")
    void rejectsWhenDataSourceMissing() {
        DefaultCacheStrategyRepository noStore = new DefaultCacheStrategyRepository(null);
        noStore.declare(Decls.USER_DETAIL, "测试声明");
        noStore.bootstrap();

        NamespacePolicy current = noStore.find("user:detail").policyOrNull();
        ChangeResult result = noStore.change(request("req-12", withMaxSize(current, 9_999L)));

        assertEquals(ChangeResult.Outcome.REJECTED, result.outcome());
        assertTrue(result.message().contains("数据源未启用"), result.message());
    }

    @Test
    @DisplayName("分页与详情视图：参数摊平，含派生本地空值 TTL")
    void viewsExposeDerivedValues() {
        NamespacePolicy current = repository.find("user:detail").policyOrNull();
        repository.change(request("req-13", withMaxSize(current, 4_000L)));

        Page<StrategyView> page = repository.page(null, 0, 10);
        assertEquals(1, page.total());
        StrategyView view = page.items().get(0);
        assertEquals("user:detail", view.namespace());
        assertEquals(4_000L, view.maxSize());
        // 本地空值 TTL = min(localTtl, centralNullTtl / N)
        assertEquals(Duration.ofSeconds(3), view.localNullTtl(), "30s / 10 = 3s");
        assertEquals(10, view.localNullTtlDivisor());
        assertTrue(view.casSupported());
        assertTrue(view.consistentWithDeclared());
        assertNotNull(view.declaredDescription());
        assertFalse(view.broken());

        assertTrue(repository.view("user:detail").isPresent());
        assertTrue(repository.view("nobody").isEmpty());
        assertTrue(repository.page("user", 0, 10).total() == 1L);
        assertTrue(repository.page("missing", 0, 10).items().isEmpty());
    }

    // ------------------------------------------------------------------ 辅助

    private StrategyChange request(String requestId, NamespacePolicy policy) {
        return new StrategyChange(requestId, "ops:zhangsan", "扩容量", 1_700_000_000_000L, policy);
    }

    private static NamespacePolicy withMaxSize(NamespacePolicy base, long maxSize) {
        return new NamespacePolicy(base.namespace(), base.description(), base.strategy(),
                base.declaredStrategy(), maxSize, base.localTtl(), base.localNullTtlDivisor(),
                base.version(), base.dynamic(), base.centralTtl(), base.centralNullTtl(),
                base.failOpen(), base.configVersion(), base.updatedAtMillis(), base.updatedBy(),
                base.reason(), base.source());
    }

    private static NamespacePolicy withVersion(NamespacePolicy base, long configVersion) {
        return new NamespacePolicy(base.namespace(), base.description(), base.strategy(),
                base.declaredStrategy(), base.maxSize(), base.localTtl(), base.localNullTtlDivisor(),
                base.version(), base.dynamic(), base.centralTtl(), base.centralNullTtl(),
                base.failOpen(), configVersion, base.updatedAtMillis(), base.updatedBy(),
                base.reason(), base.source());
    }

    private static NamespacePolicy withNamespace(NamespacePolicy base, String namespace) {
        return new NamespacePolicy(namespace, base.description(), base.strategy(),
                base.declaredStrategy(), base.maxSize(), base.localTtl(), base.localNullTtlDivisor(),
                base.version(), base.dynamic(), base.centralTtl(), base.centralNullTtl(),
                base.failOpen(), base.configVersion(), base.updatedAtMillis(), base.updatedBy(),
                base.reason(), base.source());
    }

    /**
     * 声明 fixture。
     */
    public enum Decls implements CacheStrategyDecl {

        /**
         * 静态缓存（声明描述）。
         */
        USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情（声明）");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        Decls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 计数数据源：记录 register / update 调用次数，并模拟版本推进与 CAS。
     */
    private static final class CountingDataSource implements CacheStrategyDataSource {

        private final Map<String, StoredPolicy> store = new LinkedHashMap<>();
        private int registerCalls;
        private int updateCalls;

        @Override
        public Optional<StoredPolicy> load(String namespace) {
            return Optional.ofNullable(store.get(namespace));
        }

        @Override
        public Page<StoredPolicy> query(String keyword, int page, int size) {
            return Page.of(new ArrayList<>(store.values()), keyword, page, size);
        }

        @Override
        public boolean register(StoredPolicy policy) {
            registerCalls++;
            if (store.containsKey(policy.namespace())) {
                return false;
            }
            store.put(policy.namespace(), policy);
            return true;
        }

        @Override
        public OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
            updateCalls++;
            long next = expectedConfigVersion + 1L;
            store.put(policy.namespace(), new StoredPolicy(policy.namespace(), policy.description(),
                    policy.strategy(), policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                    policy.version(), policy.dynamic(), policy.centralTtl(), policy.centralNullTtl(),
                    policy.failOpen(), next, policy.updatedAtMillis(), policy.updatedBy(), policy.reason()));
            return OptionalLong.of(next);
        }
    }
}
