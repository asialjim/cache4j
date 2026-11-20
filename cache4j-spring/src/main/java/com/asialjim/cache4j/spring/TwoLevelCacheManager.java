package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import java.util.concurrent.ConcurrentHashMap;
import com.asialjim.cache4j.core.strategy.DefaultCacheStrategyRepository;
import com.asialjim.cache4j.core.strategy.CacheStrategyLoader;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.core.strategy.CacheDeclTranslator;
import com.asialjim.cache4j.core.strategy.CacheCandidate;
import com.asialjim.cache4j.core.strategy.BuiltinDefaults;
import com.asialjim.cache4j.core.local.LocalStrategy;
import com.asialjim.cache4j.local.LocalCacheProvider;
import com.asialjim.cache4j.core.KeyEncoder;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.log.CacheLogger;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 二级缓存管理器：按定义装配并持有若干命名缓存，负责生命周期。
 *
 * <p>装配顺序对每个命名缓存是：本地缓存（来自 {@code LocalCacheProvider}，带热 key 协调器）
 * → 中央缓存（来自 {@code CentralCacheProvider}）→ 门面 {@link TwoLevelCache}。</p>
 *
 * <p>{@link #close()} 幂等：先关缓存，再按需关闭中央 provider（连接可能被多个缓存共享，
 * 所以只在 {@link Cache4jSpringOptions#ownsCentralProvider()} 为真时关闭）。</p>
 *
 * @author Asial Jim
 */
public final class TwoLevelCacheManager implements AutoCloseable {

    private static final CacheLogger LOG = CacheLogger.get(TwoLevelCacheManager.class);

    private final Map<String, TwoLevelCache<Object, Object>> caches;
    private final CentralCacheProvider centralProvider;
    private final boolean ownsCentralProvider;
    private final LocalCacheProvider localProvider;
    private final CentralCacheProvider undeclaredCentralProvider;
    private final KeyEncoder keyEncoder;
    private final HotKeyCoordinator coordinator;
    private final boolean statsEnabled;
    private final boolean undeclaredEnabled;

    /**
     * 运行期按内建默认创建过的未声明 namespace（告警与运维视图用）。
     */
    private final Set<String> undeclared = ConcurrentHashMap.newKeySet();

    private TwoLevelCacheManager(Map<String, TwoLevelCache<Object, Object>> caches,
                                 CentralCacheProvider centralProvider,
                                 boolean ownsCentralProvider,
                                 LocalCacheProvider localProvider,
                                 CentralCacheProvider undeclaredCentralProvider,
                                 KeyEncoder keyEncoder,
                                 HotKeyCoordinator coordinator,
                                 boolean statsEnabled,
                                 boolean undeclaredEnabled) {
        this.caches = caches;
        this.centralProvider = centralProvider;
        this.ownsCentralProvider = ownsCentralProvider;
        this.localProvider = localProvider;
        this.undeclaredCentralProvider = undeclaredCentralProvider;
        this.keyEncoder = keyEncoder;
        this.coordinator = coordinator;
        this.statsEnabled = statsEnabled;
        this.undeclaredEnabled = undeclaredEnabled;
    }

    /**
     * 按配置装配缓存。
     *
     * @param options 配置
     * @return 管理器
     */
    public static TwoLevelCacheManager create(Cache4jSpringOptions options) {
        Map<String, TwoLevelCache<Object, Object>> built = new LinkedHashMap<>();
        if (!options.enabled()) {
            LOG.info(CacheLogger.Subject.ASSEMBLY, "装配", "已禁用（cache4j.enabled=false），不创建任何缓存");
            return new TwoLevelCacheManager(built, options.centralProvider(), false,
                    options.localProvider(), options.centralProvider(), options.keyEncoder(),
                    null, options.statsEnabled(), false);
        }
        List<CacheDefinition> definitions = definitions(options);
        if (definitions.isEmpty()) {
            LOG.error(CacheLogger.Subject.ASSEMBLY, "装配", "至少需要一条缓存定义"
                    + "（程序化定义 / META-INF/services 声明 / @CacheNamespace 扫描 三者皆空）");
            throw new IllegalStateException("至少需要一条缓存定义");
        }
        // 同一个中央 provider 的热 key 协调器被所有 DYNAMIC 缓存共享
        HotKeyCoordinator coordinator = options.centralProvider().hotKeyCoordinator().orElse(null);

        for (CacheDefinition definition : definitions) {
            String namespace = definition.namespace();
            if (built.containsKey(namespace)) {
                LOG.error(CacheLogger.Subject.ASSEMBLY, "装配", "重复的缓存名：namespace=" + namespace);
                throw new IllegalStateException("重复的缓存名：" + namespace);
            }
            LocalCacheSpec localSpec = definition.local();
            localSpec.validate();

            LocalCache<Object> local = options.localProvider().create(localSpec, coordinator);
            CentralCache<Object> central = options.centralProvider().create(definition.central());

            TwoLevelCache<Object, Object> cache = TwoLevelCache.<Object, Object>builder(namespace)
                    .keyEncoder(options.keyEncoder())
                    .local(local)
                    .central(central)
                    .hotKeyCoordinator(coordinator)
                    .statsEnabled(options.statsEnabled())
                    .build();
            built.put(namespace, cache);
            LOG.info(CacheLogger.Subject.ASSEMBLY, "装配", "namespace=" + namespace + "，装配完成，strategy=" + localSpec.strategy()
                    + "，localTtl=" + localSpec.ttl()
                    + "，centralTtl=" + definition.central().ttl()
                    + "，coordinator=" + (coordinator == null ? "none" : coordinator.getClass().getSimpleName()));
        }
        LOG.info(CacheLogger.Subject.ASSEMBLY, "装配", "共装配 " + built.size() + " 个缓存："
                + String.join(", ", built.keySet()));
        return new TwoLevelCacheManager(built, options.centralProvider(),
                options.ownsCentralProvider(), options.localProvider(), options.centralProvider(),
                options.keyEncoder(), coordinator, options.statsEnabled(),
                options.undeclaredEnabled());
    }

    /**
     * 汇总装配来源：
     * ① 程序化定义（{@code options.cache(...)}，<strong>原样保留</strong>——它是完整 spec，
     *    不能再走"声明 + 内建默认"，否则 custom 的 TTL/协调模式会被覆盖）；
     * ② {@code META-INF/services} 文件声明；③ {@code @CacheNamespace} 包扫描；④ 显式传入的枚举。
     *
     * <p>后三者由 {@link CacheDeclTranslator} 解析成"声明 + 内建默认"。
     * 同一 namespace 出现在<strong>不同来源</strong>属冲突，启动失败（不静默取其一）。</p>
     *
     * @param options 配置
     * @return 定义列表（保持登记顺序：程序化在前）
     */
    private static List<CacheDefinition> definitions(Cache4jSpringOptions options) {
        Map<String, CacheDefinition> merged = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();

        for (CacheDefinition definition : options.definitions()) {
            put(merged, origins, definition, "程序化定义");
        }
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        CacheDeclTranslator translator = new CacheDeclTranslator();
        for (CacheCandidate candidate : new CacheStrategyLoader(classLoader).load()) {
            put(merged, origins, candidate.definition(), candidate.origin());
        }
        for (Class<?> type : new SpringCacheDeclScanner(classLoader).scan(options.declPackages())) {
            for (CacheCandidate candidate : translator.translate(type, "Spring 扫描")) {
                put(merged, origins, candidate.definition(), candidate.origin());
            }
        }
        for (Class<?> type : options.declEnums()) {
            for (CacheCandidate candidate : translator.translate(type, "显式传入")) {
                put(merged, origins, candidate.definition(), candidate.origin());
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static void put(Map<String, CacheDefinition> merged, Map<String, String> origins,
                            CacheDefinition definition, String origin) {
        String previous = origins.putIfAbsent(definition.namespace(), origin);
        if (previous != null && !previous.equals(origin)) {
            throw new IllegalStateException("namespace 冲突：" + definition.namespace()
                    + " 同时来自 " + previous + " 与 " + origin);
        }
        merged.putIfAbsent(definition.namespace(), definition);
    }

    /**
     * @param name 缓存名
     * @return 缓存；不存在返回空
     */
    /**
     * 取缓存；业务用了未声明的 namespace 时按内建默认自动创建（可用配置关闭）。
     *
     * <p>自动创建不报错——启动期无法穷举业务会用哪些缓存名（{@code @Cacheable} 的 name 可为
     * SpEL、{@code getCache(name)} 的名字来自运行时数据），强校验只会把拼写错误变成更难定位的
     * 启动失败。但必须<strong>可见</strong>：每个未声明的 namespace 告警一次 + 计数。</p>
     *
     * @param name 缓存名
     * @return 缓存；关闭自动创建且未声明时返回空
     */
    public Optional<TwoLevelCache<Object, Object>> cache(String name) {
        TwoLevelCache<Object, Object> cached = caches.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!undeclaredEnabled || name == null) {
            return Optional.empty();
        }
        synchronized (caches) {
            TwoLevelCache<Object, Object> again = caches.get(name);
            if (again != null) {
                return Optional.of(again);
            }
            return Optional.of(createUndeclared(name));
        }
    }

    /**
     * 按内建默认装配一个未声明的缓存，并告警一次。
     *
     * @param name 缓存名
     * @return 缓存
     */
    private TwoLevelCache<Object, Object> createUndeclared(String name) {
        undeclared.add(name);
        LOG.warn(CacheLogger.Subject.ASSEMBLY, "声明",
                name + " 未经 CacheStrategyDecl 声明，按内建默认创建，请补充到声明枚举"
                        + "（未声明 namespace 累计 " + undeclared.size() + " 个）");
        CacheDefinition definition = BuiltinDefaults.definitionOf(name, LocalStrategy.STATIC);
        LocalCache<Object> local = localProvider.create(definition.local(), coordinator);
        CentralCache<Object> central = undeclaredCentralProvider.create(definition.central());
        TwoLevelCache<Object, Object> created = TwoLevelCache.<Object, Object>builder(name)
                .keyEncoder(keyEncoder)
                .local(local)
                .central(central)
                .hotKeyCoordinator(coordinator)
                .localNullTtlDivisor(definition.localNullTtlDivisor())
                .statsEnabled(statsEnabled)
                .build();
        synchronized (caches) {
            caches.put(name, created);
        }
        return created;
    }

    /**
     * @return 运行期按内建默认创建过的未声明 namespace
     */
    public Set<String> undeclaredNamespaces() {
        return Set.copyOf(undeclared);
    }

    /**
     * @return 全部缓存名（保持定义顺序）
     */
    public Set<String> names() {
        return caches.keySet();
    }

    /**
     * @return 全部缓存视图
     */
    public Collection<TwoLevelCache<Object, Object>> caches() {
        return caches.values();
    }

    @Override
    public void close() {
        LOG.info(CacheLogger.Subject.ASSEMBLY, "关闭", "关闭 " + caches.size() + " 个缓存"
                + (ownsCentralProvider ? "，并关闭中央 provider" : ""));
        caches.values().forEach(cache -> {
            try {
                cache.close();
            } catch (RuntimeException ignored) {
                // 关闭阶段的异常不应阻断其它缓存释放
            }
        });
        caches.clear();
        if (ownsCentralProvider && centralProvider != null) {
            centralProvider.close();
        }
    }
}
