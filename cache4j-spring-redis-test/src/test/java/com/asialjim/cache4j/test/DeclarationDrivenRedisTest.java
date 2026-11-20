package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.core.strategy.CacheNamespace;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.spring.Cache4jAutoScanConfiguration;
import com.asialjim.cache4j.spring.TwoLevelCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枚举声明驱动：只写 {@code @CacheNamespace} 声明 + 让 component-scan 收进来，
 * 三种本地策略就自动装配进缓存体系，{@code @Cacheable} 直接可用。
 *
 * <p>与其他集成用例的区别：这里<strong>不写任何程序化 {@code CacheDefinition}</strong>，
 * 也不配 {@code cache4j.decl.packages}——完全靠声明枚举驱动。</p>
 *
 * <p>需要真实 Redis（{@code 127.0.0.1:6379}），不可达时整组跳过；
 * 可用 {@code -Dcache4j.test.redis.host/port/password} 覆盖。</p>
 *
 * <p><strong>跑完不清理 Redis。</strong>每个用例开始前会清一次自己的 namespace（保证可重复），
 * 最后一个用例 {@link #leaveSamplesForInspection()} 会写入三条样本并留在库里，方便直接观察：</p>
 *
 * <pre>
 * redis-cli --scan --pattern 'cache4j:{decl:*'
 * redis-cli hgetall 'cache4j:{decl:static}:__rev'      # 版本表（field=key，value=rev|expireAt）
 * redis-cli get    'cache4j:{decl:static}:s4:keep'     # 数据（s4 = 编码后的 key，keep 是 4 字符）
 * </pre>
 *
 * @author Asial Jim
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeclarationDrivenRedisTest {

    private static final String NS_STATIC = "decl:static";

    private static final String NS_NONE = "decl:none";

    private static final String NS_DYNAMIC = "decl:dynamic";

    private static final String[] NAMESPACES = {NS_STATIC, NS_NONE, NS_DYNAMIC};

    /**
     * key 布局：直接以 namespace 开头，没有前缀、没有 hash tag。
     */

    private static final AtomicInteger STATIC_LOADS = new AtomicInteger();

    private static final AtomicInteger NONE_LOADS = new AtomicInteger();

    private static final AtomicInteger DYNAMIC_LOADS = new AtomicInteger();

    private AnnotationConfigApplicationContext context;

    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedisTestEnvironment.redisAvailable(),
                "Redis 不可达（" + RedisTestEnvironment.HOST + ":" + RedisTestEnvironment.PORT + "），跳过");
        resetCounters();
        connectionFactory = RedisTestEnvironment.connectionFactory();
        clearNamespaces();

        context = new AnnotationConfigApplicationContext();
        context.register(DeclConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
        if (connectionFactory != null) {
            // 刻意不清 Redis：跑完留在库里，方便直接进 redis-cli 观察中央缓存的内容
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }

    @Test
    @Order(1)
    @DisplayName("测试里的 cacheNames 常量与枚举声明一致（防漂移）")
    void cacheNamesMatchDeclarations() {
        assertEquals(Decl.STATIC.getNamespace(), NS_STATIC);
        assertEquals(Decl.NONE.getNamespace(), NS_NONE);
        assertEquals(Decl.DYNAMIC.getNamespace(), NS_DYNAMIC);
    }

    @Test
    @Order(2)
    @DisplayName("三条声明自动装配成三条缓存，不需要任何程序化定义")
    void declarationsDriveAssembly() {
        TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
        assertEquals(Set.of(NS_STATIC, NS_NONE, NS_DYNAMIC), manager.names());
    }

    @Test
    @Order(3)
    @DisplayName("STATIC：本地有副本，第二次调用不再执行方法体，Redis 里有数据")
    void staticDeclarationServesFromLocal() {
        DeclService service = context.getBean(DeclService.class);

        assertEquals("static-7", service.staticValue("7"));
        assertEquals("static-7", service.staticValue("7"));
        assertEquals(1, STATIC_LOADS.get(), "第二次应命中本地，不再查数据源");

        assertTrue(localHasKey(NS_STATIC), "STATIC 策略应当把副本放进本地");
        assertTrue(redisHasKey(NS_STATIC), "中央也应写入，供其他节点/回源使用");
    }

    @Test
    @Order(4)
    @DisplayName("NONE：本地不放副本，靠中央命中，Redis 里有数据")
    void noneDeclarationOnlyUsesCentral() {
        DeclService service = context.getBean(DeclService.class);

        assertEquals("none-7", service.noneValue("7"));
        assertEquals("none-7", service.noneValue("7"));
        assertEquals(1, NONE_LOADS.get(), "第二次应命中中央，不再查数据源");

        assertFalse(localHasKey(NS_NONE), "NONE 策略不应把副本放进本地");
        assertTrue(redisHasKey(NS_NONE), "数据只应在中央");
    }

    @Test
    @Order(5)
    @DisplayName("DYNAMIC：冷 key 不进本地，但仍能从中央命中")
    void dynamicDeclarationKeepsColdKeyOutOfLocal() {
        DeclService service = context.getBean(DeclService.class);

        assertEquals("dynamic-7", service.dynamicValue("7"));
        assertEquals(1, DYNAMIC_LOADS.get());

        assertFalse(localHasKey(NS_DYNAMIC),
                "冷 key 不该占本地内存——hotkey 统计的是读频次，写没有资格占本地（D23）");
        assertTrue(redisHasKey(NS_DYNAMIC), "写仍然落在中央");

        assertEquals("dynamic-7", service.dynamicValue("7"));
        assertEquals(1, DYNAMIC_LOADS.get(), "第二次应命中中央");
    }

    /**
     * 最后跑：三条策略各写一条，<strong>刻意留在 Redis 里</strong>给人工观察。
     *
     * <p>它会先把三个 namespace 清空，所以跑完之后库里正好是这三条样本。</p>
     */
    @Test
    @Order(99)
    @DisplayName("三条策略都写入中央，并把样本留在 Redis 里供观察")
    void leaveSamplesForInspection() {
        DeclService service = context.getBean(DeclService.class);
        assertEquals("static-keep", service.staticValue("keep"));
        assertEquals("none-keep", service.noneValue("keep"));
        assertEquals("dynamic-keep", service.dynamicValue("keep"));

        assertTrue(redisHasKey(NS_STATIC), "STATIC 应写入中央：" + NS_STATIC);
        assertTrue(redisHasKey(NS_NONE), "NONE 应写入中央：" + NS_NONE);
        assertTrue(redisHasKey(NS_DYNAMIC), "DYNAMIC 应写入中央：" + NS_DYNAMIC);

        System.out.println("[DeclarationDrivenRedisTest] 已保留样本，可用 "
                + "redis-cli --scan --pattern 'cache4j:{decl:*' 观察");
    }

    // ------------------------------------------------------------------ 断言辅助

    private boolean localHasKey(String namespace) {
        TwoLevelCacheManager manager = context.getBean(TwoLevelCacheManager.class);
        return manager.cache(namespace).orElseThrow().local().keys().stream()
                .anyMatch(key -> namespace.equals(key.namespace()));
    }

    private boolean redisHasKey(String namespace) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Set<byte[]> keys = connection.keyCommands().keys(pattern(namespace));
            return keys != null && !keys.isEmpty();
        }
    }

    private void clearNamespaces() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            for (String namespace : NAMESPACES) {
                Set<byte[]> keys = connection.keyCommands().keys(pattern(namespace));
                if (keys != null && !keys.isEmpty()) {
                    connection.keyCommands().del(keys.toArray(new byte[0][]));
                }
            }
        }
    }

    private static byte[] pattern(String namespace) {
        return (namespace + ":*").getBytes(StandardCharsets.UTF_8);
    }

    private static void resetCounters() {
        STATIC_LOADS.set(0);
        NONE_LOADS.set(0);
        DYNAMIC_LOADS.set(0);
    }

    // ------------------------------------------------------------------ 声明

    /**
     * 缓存策略声明——业务侧要写的就只有这一段。
     *
     * <p>三种策略各一条，全部靠 {@link CacheNamespace} 标记 + component-scan 发现，
     * 不需要程序化 {@code CacheDefinition}，也不需要配 {@code cache4j.decl.packages}。</p>
     */
    @CacheNamespace
    public enum Decl implements CacheStrategyDecl {

        /**
         * 静态本地缓存。
         */
        STATIC("decl:static", LocalStrategy.STATIC, "静态本地缓存（声明驱动测试）"),

        /**
         * 禁用本地缓存，只走中央。
         */
        NONE("decl:none", LocalStrategy.NONE, "只走中央缓存（声明驱动测试）"),

        /**
         * 动态本地缓存。
         */
        DYNAMIC("decl:dynamic", LocalStrategy.DYNAMIC, "动态本地缓存（声明驱动测试）");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        Decl(String namespace, LocalStrategy strategy, String description) {
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

    // ------------------------------------------------------------------ 装配

    /**
     * 业务侧的全部配置：激活 + 让声明枚举被扫到 + 给 Redis 连接。
     *
     * <p>注意这里<strong>没有</strong> {@code CentralCacheProvider} 的 {@code @Bean}：
     * {@code cache4j-spring-redis} 在 classpath 上，{@link Cache4jAutoScanConfiguration}
     * 扫到它的配置类后自动提供了 {@code cache4jCentralProvider}。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @Import(Cache4jAutoScanConfiguration.class)
    @ComponentScan(
            basePackages = "com.asialjim.cache4j.test",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = CacheNamespace.class))
    static class DeclConfig {

        /**
         * @return Redis 连接工厂（客户端由业务选）
         */
        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory redisConnectionFactory() {
            return RedisTestEnvironment.connectionFactory();
        }

        /**
         * @return 被缓存的业务 bean
         */
        @Bean
        DeclService declService() {
            return new DeclService();
        }
    }

    /**
     * 被缓存的业务方法：三条声明各一个。
     */
    static class DeclService {

        @Cacheable(cacheNames = NS_STATIC, key = "#id")
        public String staticValue(String id) {
            STATIC_LOADS.incrementAndGet();
            return "static-" + id;
        }

        @Cacheable(cacheNames = NS_NONE, key = "#id")
        public String noneValue(String id) {
            NONE_LOADS.incrementAndGet();
            return "none-" + id;
        }

        @Cacheable(cacheNames = NS_DYNAMIC, key = "#id")
        public String dynamicValue(String id) {
            DYNAMIC_LOADS.incrementAndGet();
            return "dynamic-" + id;
        }
    }
}
