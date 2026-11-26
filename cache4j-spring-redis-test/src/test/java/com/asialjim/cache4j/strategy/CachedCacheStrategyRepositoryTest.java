package com.asialjim.cache4j.strategy;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.central.InMemoryCentralCache;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheStrategyDataSource;
import com.asialjim.cache4j.core.strategy.CacheStrategyAdministrator;
import com.asialjim.cache4j.core.strategy.CacheStrategyCache;
import com.asialjim.cache4j.core.strategy.StrategySelfCacheOptions;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.core.strategy.ChangeResult;
import com.asialjim.cache4j.core.strategy.NamespacePolicy;
import com.asialjim.cache4j.core.strategy.Page;
import com.asialjim.cache4j.core.strategy.PolicyLookup;
import com.asialjim.cache4j.core.strategy.StoredPolicy;
import com.asialjim.cache4j.core.strategy.StrategyChange;
import com.asialjim.cache4j.core.strategy.StrategyView;
import com.asialjim.cache4j.local.LocalCaches;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自举缓存门面：启动回填、命中不自举、远端变更经版本对账传播、变更写穿。
 *
 * @author Asial Jim
 */
class CacheStrategyCacheTest {

    private static final String NS = "user:detail";

    @Test
    @DisplayName("bootstrap 把解析结果回填自举缓存；之后 find 不再打数据源")
    void bootstrapFillsSelfCache() {
        Fixture fixture = new Fixture();
        fixture.cache.declare(Decls.USER_DETAIL, "测试声明");
        fixture.cache.bootstrap();
        long loadsAfterBootstrap = fixture.dataSource.loads.get();

        NamespacePolicy policy = fixture.cache.find(NS).policyOrNull();
        assertEquals(LocalStrategy.STATIC, policy.strategy());
        assertEquals(loadsAfterBootstrap, fixture.dataSource.loads.get(),
                "命中自举缓存时不应再访问数据源");
    }

    @Test
    @DisplayName("自举缓存未命中：回源数据源并回填")
    void missFallsBackToDataSource() {
        Fixture fixture = new Fixture();
        fixture.dataSource.seed(stored(LocalStrategy.NONE, 3L, "仅中央", 1_000L));
        fixture.cache.declare(Decls.USER_DETAIL, "测试声明");
        fixture.cache.bootstrap();
        fixture.cache.selfCache().clear();
        long before = fixture.dataSource.loads.get();

        NamespacePolicy policy = fixture.cache.find(NS).policyOrNull();
        assertNotNull(policy, "回源后应有结果");
        assertEquals(LocalStrategy.NONE, policy.strategy(), "应使用数据源的策略");
        assertEquals(before + 1, fixture.dataSource.loads.get(), "未命中回源一次");
    }

    @Test
    @DisplayName("跨节点传播：A 变更后，B 在对账窗口内由自举缓存发现并重新解析")
    void changePropagatesToOtherNode() {
        Fixture nodeA = new Fixture();
        nodeA.dataSource.seed(stored(LocalStrategy.STATIC, 1L, "初始", 1_000L));
        nodeA.cache.declare(Decls.USER_DETAIL, "声明");
        nodeA.cache.bootstrap();

        // 节点 B 共享同一个数据源与中央缓存
        CacheStrategyCache nodeB = nodeA.newPeer();
        nodeB.declare(Decls.USER_DETAIL, "声明");
        nodeB.bootstrap();

        NamespacePolicy currentB = nodeB.find(NS).policyOrNull();
        assertEquals(1_000L, currentB.maxSize());

        // A 改配置
        NamespacePolicy target = withMaxSize(nodeA.cache.find(NS).policyOrNull(), 9_000L);
        ChangeResult result = nodeA.admin.change(
                new StrategyChange("req-a", "ops", "扩容", nodeA.clock.get(), target));
        assertEquals(ChangeResult.Outcome.APPLIED, result.outcome(), result.message());

        // B 推进一个对账窗口后再读：应看到新配置
        nodeA.clock.addAndGet(Duration.ofSeconds(6).toMillis());
        NamespacePolicy refreshedB = nodeB.find(NS).policyOrNull();
        assertEquals(9_000L, refreshedB.maxSize(), "5s 对账窗口内应看到 A 的变更");
    }

    @Test
    @DisplayName("变更成功后写穿自举缓存：本节点立即读到新配置")
    void changeWritesThroughToSelfCache() {
        Fixture fixture = new Fixture();
        fixture.cache.declare(Decls.USER_DETAIL, "声明");
        fixture.cache.bootstrap();

        NamespacePolicy target = withMaxSize(fixture.cache.find(NS).policyOrNull(), 4_000L);
        // bootstrap 阶段解析时读过一次数据源，这里取变更前的基线
        long before = fixture.dataSource.loads.get();
        fixture.admin.change(new StrategyChange("req-w", "ops", "扩容", fixture.clock.get(), target));

        assertEquals(4_000L, fixture.cache.find(NS).policyOrNull().maxSize());
        assertEquals(before, fixture.dataSource.loads.get(), "写穿后读策略不应再回源数据源");
    }

    @Test
    @DisplayName("从未声明、数据源也没有的 namespace：find 返回 Absent，负缓存生效")
    void absentWhenNeverDeclared() {
        Fixture fixture = new Fixture();
        fixture.cache.declare(Decls.USER_DETAIL, "声明");
        fixture.cache.bootstrap();
        fixture.cache.selfCache().clear();

        // 已声明的 namespace 会被 bootstrap 播种进数据源，所以它不再 Absent
        assertInstanceOf(PolicyLookup.Found.class, fixture.cache.find(NS));

        // 真正 Absent 的是"谁都没提过"的 namespace
        assertInstanceOf(PolicyLookup.Absent.class, fixture.cache.find("never:declared"));
    }

    @Test
    @DisplayName("运维分页与详情走门面委托（不经过自举缓存）")
    void adminDelegates() {
        Fixture fixture = new Fixture();
        // 列表页只覆盖数据源里的记录（仅声明、尚未落库的条目用详情视图查）
        fixture.dataSource.seed(stored(LocalStrategy.STATIC, 1L, "初始", 1_000L));
        fixture.cache.declare(Decls.USER_DETAIL, "声明");
        fixture.cache.bootstrap();

        Page<StrategyView> page = fixture.admin.page(null, 0, 10);
        assertEquals(1L, page.total());
        assertEquals(NS, page.items().get(0).namespace());
        assertTrue(fixture.admin.view(NS).isPresent());
    }

    // ------------------------------------------------------------------ fixture

    private static StoredPolicy stored(LocalStrategy strategy, long configVersion, String reason,
                                       Long maxSize) {
        return new StoredPolicy(NS, "运维说明", strategy, maxSize, Duration.ofSeconds(30), 10,
                VersionSpec.defaults(), null,
                Duration.ofMinutes(30), Duration.ofSeconds(30), true,
                configVersion, 1_700_000_000_000L, "ops", reason);
    }

    private static NamespacePolicy withMaxSize(NamespacePolicy base, long maxSize) {
        return new NamespacePolicy(base.namespace(), base.description(), base.strategy(),
                base.declaredStrategy(), maxSize, base.localTtl(), base.localNullTtlDivisor(),
                base.version(), base.dynamic(), base.centralTtl(), base.centralNullTtl(),
                base.failOpen(), base.configVersion(), base.updatedAtMillis(), base.updatedBy(),
                base.reason(), base.source());
    }

    /**
     * 一个"节点"：共享数据源与中央缓存，单独的自举缓存与时钟。
     */
    private static final class Fixture {

        private final AtomicLong clock;

        private final MemoryDataSource dataSource;

        private final CentralCache<StoredPolicy> central;

        private final CacheStrategyCache cache;

        private final CacheStrategyAdministrator admin;

        Fixture() {
            this(new MemoryDataSource(), new AtomicLong(1_700_000_000_000L));
        }

        private Fixture(MemoryDataSource dataSource, AtomicLong clock) {
            this.clock = clock;
            this.dataSource = dataSource;
            this.central = new InMemoryCentralCache<>(
                    StrategySelfCacheOptions.defaults().cacheStrategy(), clock::get);
            this.cache = build(dataSource, central, clock);
            this.admin = new CacheStrategyAdministrator(this.cache);
        }

        private static CacheStrategyCache build(MemoryDataSource dataSource,
                                                CentralCache<StoredPolicy> central,
                                                AtomicLong clock) {
            StrategySelfCacheOptions options = StrategySelfCacheOptions.defaults();
            return new CacheStrategyCache(dataSource, options,
                    LocalCaches.create(options.localSpec().toStrategy(), null, () -> clock.get() * 1_000_000L,
                            central::revisions, central.cacheStrategy().failOpen()),
                    central,
                    () -> clock.get() * 1_000_000L,
                    clock::get);
        }

        /**
         * @return 与当前节点共享数据源与中央缓存的另一个节点
         */
        CacheStrategyCache newPeer() {
            return build(dataSource, central, clock);
        }
    }

    /**
     * 内存数据源（与 {@link Fixture} 配套；记录 load 次数）。
     */
    private static final class MemoryDataSource implements CacheStrategyDataSource {

        private final Map<String, StoredPolicy> store = new LinkedHashMap<>();
        private final AtomicLong loads = new AtomicLong();

        void seed(StoredPolicy policy) {
            store.put(policy.namespace(), policy);
        }

        @Override
        public Optional<StoredPolicy> load(String namespace) {
            loads.incrementAndGet();
            return Optional.ofNullable(store.get(namespace));
        }

        @Override
        public Page<StoredPolicy> query(String keyword, int page, int size) {
            return Page.of(new ArrayList<>(store.values()), keyword, page, size);
        }

        @Override
        public boolean register(StoredPolicy policy) {
            if (store.containsKey(policy.namespace())) {
                return false;
            }
            store.put(policy.namespace(), policy);
            return true;
        }

        @Override
        public OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
            StoredPolicy current = store.get(policy.namespace());
            if (current != null && current.configVersion() != expectedConfigVersion) {
                return OptionalLong.empty();
            }
            long next = expectedConfigVersion + 1L;
            store.put(policy.namespace(), new StoredPolicy(policy.namespace(), policy.description(),
                    policy.strategy(), policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                    policy.version(), policy.dynamic(), policy.centralTtl(), policy.centralNullTtl(),
                    policy.failOpen(), next, policy.updatedAtMillis(), policy.updatedBy(), policy.reason()));
            return OptionalLong.of(next);
        }
    }

    /**
     * 声明 fixture。
     */
    public enum Decls implements CacheStrategyDecl {

        /**
         * 静态缓存。
         */
        USER_DETAIL(NS, LocalStrategy.STATIC, "用户详情");

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
}
