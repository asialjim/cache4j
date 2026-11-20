package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.local.LocalStrategy;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓库：登记语义（不覆盖、冲突即失败）、三层解析、三态查询、数据源不可达降级。
 *
 * @author Asial Jim
 */
class DefaultCacheStrategyRepositoryTest {

    // ------------------------------------------------------------------ 无数据源

    @Test
    @DisplayName("无数据源：声明 + 内建默认即可解析，来源为 DECLARED")
    void resolvesFromDeclarationWithoutDataSource() {
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(null);
        repository.declare(Decls.USER_DETAIL, "测试声明");

        List<String> namespaces = repository.bootstrap();

        assertEquals(List.of("user:detail"), namespaces);
        NamespacePolicy policy = repository.find("user:detail").policyOrNull();
        assertEquals(LocalStrategy.STATIC, policy.strategy());
        assertEquals(LocalStrategy.STATIC, policy.declaredStrategy());
        assertEquals(BuiltinDefaults.MAX_SIZE, policy.maxSize());
        assertEquals(BuiltinDefaults.LOCAL_TTL, policy.localTtl());
        assertEquals(BuiltinDefaults.CENTRAL_TTL, policy.centralTtl());
        assertEquals(BuiltinDefaults.CENTRAL_NULL_TTL, policy.centralNullTtl());
        assertEquals(NamespacePolicy.Source.DECLARED, policy.source());
        assertTrue(policy.consistentWithDeclared());
    }

    @Test
    @DisplayName("未登记的 namespace：find 返回 Absent（不是异常）")
    void absentForUnknownNamespace() {
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(null);
        repository.bootstrap();

        assertInstanceOf(PolicyLookup.Absent.class, repository.find("nobody"));
        assertTrue(repository.all().isEmpty());
        assertTrue(repository.definitions().isEmpty());
    }

    @Test
    @DisplayName("definitions()：把生效配置转成装配定义")
    void convertsToDefinitions() {
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(null);
        repository.declare(Decls.USER_DETAIL, "测试声明");
        repository.bootstrap();

        List<CacheDefinition> definitions = repository.definitions();
        assertEquals(1, definitions.size());
        assertEquals("user:detail", definitions.get(0).namespace());
        definitions.get(0).validate();
    }

    // ------------------------------------------------------------------ 登记语义

    @Test
    @DisplayName("同一来源重复登记：幂等，不报错")
    void duplicateDeclarationFromSameOriginIsIdempotent() {
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(null);
        repository.declare(Decls.USER_DETAIL, "同一来源");
        repository.declare(Decls.USER_DETAIL, "同一来源");

        assertEquals(List.of("user:detail"), repository.bootstrap());
    }

    @Test
    @DisplayName("不同来源声明同一 namespace：冲突，报双方来源")
    void conflictingDeclarationsFail() {
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(null);
        repository.declare(Decls.USER_DETAIL, "来源A");

        CacheException e = assertThrows(CacheException.class,
                () -> repository.declare(Decls.USER_DETAIL_OTHER, "来源B"));
        assertTrue(e.getMessage().contains("namespace 冲突"), e.getMessage());
        assertTrue(e.getMessage().contains("来源A") && e.getMessage().contains("来源B"), e.getMessage());
    }

    // ------------------------------------------------------------------ 数据源优先

    @Test
    @DisplayName("数据源有记录：以数据源为准，声明值降级为 declaredStrategy")
    void repositoryWinsOverDeclaration() {
        FakeDataSource dataSource = new FakeDataSource();
        dataSource.put(new StoredPolicy("user:detail", "运维改写过的说明", LocalStrategy.NONE, null, null,
                null, null, null, null, false, 7L, 1_700_000_000_000L, "ops:zhangsan", "仅中央"));
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(dataSource);
        repository.declare(Decls.USER_DETAIL, "测试声明");

        repository.bootstrap();
        NamespacePolicy policy = repository.find("user:detail").policyOrNull();

        assertEquals(LocalStrategy.NONE, policy.strategy(), "生效策略取数据源");
        assertEquals(LocalStrategy.STATIC, policy.declaredStrategy(), "声明值保留用于审计");
        assertEquals("运维改写过的说明", policy.description());
        assertEquals(7L, policy.configVersion());
        assertEquals("ops:zhangsan", policy.updatedBy());
        assertFalse(policy.consistentWithDeclared(), "生效与声明不一致属正常，不告警");
        assertEquals(NamespacePolicy.Source.REPOSITORY, policy.source());
        assertFalse(policy.failOpen(), "数据源明确写了 false，应生效");
    }

    @Test
    @DisplayName("数据源记录字段缺省：用内建默认补齐")
    void storedPolicyNullFieldsFallBackToBuiltinDefaults() {
        FakeDataSource dataSource = new FakeDataSource();
        dataSource.put(new StoredPolicy("user:detail", null, LocalStrategy.DYNAMIC, null, null,
                null, null, null, null, null, 3L, 0L, null, null));
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(dataSource);
        repository.declare(Decls.USER_DETAIL, "测试声明");

        repository.bootstrap();
        NamespacePolicy policy = repository.find("user:detail").policyOrNull();

        assertEquals(LocalStrategy.DYNAMIC, policy.strategy());
        assertEquals("用户详情", policy.description(), "缺省时用声明值");
        assertEquals(BuiltinDefaults.MAX_SIZE, policy.maxSize());
        assertEquals(BuiltinDefaults.LOCAL_TTL, policy.localTtl());
        assertTrue(policy.dynamic() != null, "DYNAMIC 缺省时补内建默认 DynamicSpec");
        assertTrue(policy.failOpen(), "缺省时 failOpen=true");
    }

    @Test
    @DisplayName("数据源不可达：降级为声明 + 内建默认，不抛异常")
    void unavailableDataSourceFallsBackToDeclaration() {
        CacheStrategyDataSource broken = new CacheStrategyDataSource() {
            @Override
            public Optional<StoredPolicy> load(String namespace) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public List<StoredPolicy> loadAll() {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean register(StoredPolicy policy) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
                throw new IllegalStateException("redis down");
            }
        };
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(broken);
        repository.declare(Decls.USER_DETAIL, "测试声明");

        assertEquals(List.of("user:detail"), repository.bootstrap());
        NamespacePolicy policy = repository.find("user:detail").policyOrNull();
        assertEquals(LocalStrategy.STATIC, policy.strategy());
        assertEquals(NamespacePolicy.Source.DECLARED, policy.source());
    }

    @Test
    @DisplayName("启动装配不调用 loadAll（预装配集合由声明决定，孤儿不参与）")
    void bootstrapNeverCallsLoadAll() {
        FakeDataSource dataSource = new FakeDataSource();
        dataSource.put(new StoredPolicy("orphan:cache", null, LocalStrategy.STATIC, null, null,
                null, null, null, null, null, 1L, 0L, null, null));
        DefaultCacheStrategyRepository repository = new DefaultCacheStrategyRepository(dataSource);
        repository.declare(Decls.USER_DETAIL, "测试声明");

        repository.bootstrap();

        assertEquals(0, dataSource.loadAllCalls, "启动装配不应遍历数据源");
        assertEquals(1, repository.all().size(), "孤儿配置不参与装配");
        assertEquals("user:detail", repository.all().get(0).namespace());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * 供测试使用的声明。
     */
    public enum Decls implements CacheStrategyDecl {

        /**
         * 静态缓存。
         */
        USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情"),

        /**
         * 同 namespace、不同来源（用于冲突测试）。
         */
        USER_DETAIL_OTHER("user:detail", LocalStrategy.NONE, "用户详情（另一个声明）");

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
     * 内存数据源 fixture（记录 loadAll 调用次数）。
     */
    private static final class FakeDataSource implements CacheStrategyDataSource {

        private final Map<String, StoredPolicy> store = new LinkedHashMap<>();
        private int loadAllCalls;

        void put(StoredPolicy policy) {
            store.put(policy.namespace(), policy);
        }

        @Override
        public Optional<StoredPolicy> load(String namespace) {
            return Optional.ofNullable(store.get(namespace));
        }

        @Override
        public List<StoredPolicy> loadAll() {
            loadAllCalls++;
            return new ArrayList<>(store.values());
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
            if (current == null || current.configVersion() != expectedConfigVersion) {
                return OptionalLong.empty();
            }
            long next = Math.max(System.currentTimeMillis(), current.configVersion() + 1);
            store.put(policy.namespace(), policy);
            return OptionalLong.of(next);
        }
    }

    /**
     * 仅用于类型占位，避免未使用导入告警。
     *
     * @return 一个固定时长
     */
    private static Duration unusedPlaceholder() {
        return Duration.ZERO;
    }
}
