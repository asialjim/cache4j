package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.twolevel.Cache4jImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import java.util.concurrent.ConcurrentHashMap;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.core.strategy.CacheStrategyRepository;
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
import com.asialjim.cache4j.core.Cache4j;
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
 * → 中央缓存（来自 {@code CentralCacheProvider}）→ 门面 {@link Cache4j}。</p>
 *
 * <p>{@link #close()} 幂等：先关缓存，再按需关闭中央 provider（连接可能被多个缓存共享，
 * 所以只在 {@link Cache4jSpringOptions#ownsCentralProvider()} 为真时关闭）。</p>
 *
 * @author Asial Jim
 */
public final class TwoLevelCacheManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TwoLevelCacheManager.class);

    private final Map<String, Cache4j<Object, Object>> caches;
    private final CentralCacheProvider centralProvider;
    private final boolean ownsCentralProvider;
    private final LocalCacheProvider localProvider;
    private final CentralCacheProvider undeclaredCentralProvider;
    private final KeyEncoder keyEncoder;
    private final HotKeyCoordinator coordinator;
    private final boolean undeclaredEnabled;

    /**
     * 运行期按内建默认创建过的未声明 namespace（告警与运维视图用）。
     */
    private final Set<String> undeclared = ConcurrentHashMap.newKeySet();

    private TwoLevelCacheManager(Map<String, Cache4j<Object, Object>> caches,
                                 CentralCacheProvider centralProvider,
                                 boolean ownsCentralProvider,
                                 LocalCacheProvider localProvider,
                                 CentralCacheProvider undeclaredCentralProvider,
                                 KeyEncoder keyEncoder,
                                 HotKeyCoordinator coordinator,
                                 boolean undeclaredEnabled) {
        this.caches = caches;
        this.centralProvider = centralProvider;
        this.ownsCentralProvider = ownsCentralProvider;
        this.localProvider = localProvider;
        this.undeclaredCentralProvider = undeclaredCentralProvider;
        this.keyEncoder = keyEncoder;
        this.coordinator = coordinator;
        this.undeclaredEnabled = undeclaredEnabled;
    }

    /**
     * 按配置装配缓存。
     *
     * @param options 配置
     * @return 管理器
     */
    public static TwoLevelCacheManager create(Cache4jSpringOptions options) {
        Map<String, Cache4j<Object, Object>> built = new LinkedHashMap<>();
        if (!options.enabled()) {
            LOG.debug("[{}]-{}-{}", "缓存装配", "装配", "已禁用（cache4j.enabled=false），不创建任何缓存");
            return new TwoLevelCacheManager(built, options.centralProvider(), false,
                    options.localProvider(), options.centralProvider(), options.keyEncoder(),
                    null, false);
        }
        List<CacheDefinition> definitions = definitions(options);
        if (definitions.isEmpty()) {
            LOG.error("[{}]-{}-{}", "缓存装配", "装配", "至少需要一条缓存定义"
                    + "（程序化定义 / META-INF/services 声明 / @CacheNamespace 扫描 三者皆空）");
            throw new IllegalStateException("至少需要一条缓存定义");
        }
        // 同一个中央 provider 的热 key 协调器被所有 DYNAMIC 缓存共享
        HotKeyCoordinator coordinator = options.centralProvider().hotKeyCoordinator().orElse(null);

        for (CacheDefinition definition : definitions) {
            String namespace = definition.namespace();
            if (built.containsKey(namespace)) {
                LOG.error("[{}]-{}-{}", "缓存装配", "装配", "重复的缓存名：namespace=" + namespace);
                throw new IllegalStateException("重复的缓存名：" + namespace);
            }
            LocalCacheSpec localSpec = definition.local();
            localSpec.validate();

            // 先有中央，再把「版本读取器」交给本地：对账是本地副本的新鲜度问题，
            // 但本地需要能问中央「这批 key 的权威版本是多少」
            CentralCache<Object> central = options.centralProvider().create(definition.central());
            LocalCache<Object> local = options.localProvider()
                    .create(localSpec, coordinator, central::revisions, definition.central().failOpen());

            Cache4j<Object, Object> cache = Cache4jImpl.<Object, Object>builder(namespace)
                    .keyEncoder(options.keyEncoder())
                    .local(local)
                    .central(central)
                    .hotKeyCoordinator(coordinator)
                    .build();
            built.put(namespace, cache);
            LOG.debug("[{}]-{}-{}", "缓存装配", "装配", "namespace=" + namespace + "，装配完成，strategy=" + localSpec.strategy()
                    + "，localTtl=" + localSpec.ttl()
                    + "，centralTtl=" + definition.central().ttl()
                    + "，coordinator=" + (coordinator == null ? "none" : coordinator.getClass().getSimpleName()));
        }
        LOG.debug("[{}]-{}-{}", "缓存装配", "装配", "共装配 " + built.size() + " 个缓存："
                + String.join(", ", built.keySet()));
        return new TwoLevelCacheManager(built, options.centralProvider(),
                options.ownsCentralProvider(), options.localProvider(), options.centralProvider(),
                options.keyEncoder(), coordinator,
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

        // ① 程序化定义永远是完整 spec，原样保留（仓库不参与）
        for (CacheDefinition definition : options.definitions()) {
            put(merged, origins, definition, "程序化定义");
        }

        List<CacheCandidate> declared = declaredCandidates(options);
        CacheStrategyRepository repository = options.strategyRepository();
        if (repository == null) {
            // ② 没有仓库：声明直接按"声明 + 内建默认"生效
            for (CacheCandidate candidate : declared) {
                put(merged, origins, candidate.definition(), candidate.origin());
            }
            return new ArrayList<>(merged.values());
        }

        // ② 有仓库：声明登记进仓库（已存在则跳过，绝不覆盖运维改过的值），
        //    再由仓库给出生效定义——运维的改动因此能覆盖代码里的声明默认值
        for (CacheCandidate candidate : declared) {
            // 用原始声明登记：description 等"声明里才有的信息"必须原样落库，
            // 包一层 ad-hoc 声明会把 description 换成来源串
            repository.declare(candidate.decl(), candidate.origin());
        }
        List<String> failures = repository.bootstrap();
        if (!failures.isEmpty()) {
            LOG.warn("[{}]-{}-{}", "缓存装配", "装配", "策略仓库自举有 " + failures.size() + " 个 namespace 失败：" + failures);
        }
        for (CacheDefinition definition : repository.definitions()) {
            merged.putIfAbsent(definition.namespace(), definition);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 汇总代码里的声明来源（文件 / 包扫描 / 显式传入）。
     *
     * @param options 配置
     * @return 声明候选
     */
    private static List<CacheCandidate> declaredCandidates(Cache4jSpringOptions options) {
        List<CacheCandidate> candidates = new ArrayList<>();
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        CacheDeclTranslator translator = new CacheDeclTranslator();
        candidates.addAll(new CacheStrategyLoader(classLoader).load());
        for (Class<?> type : new SpringCacheDeclScanner(classLoader).scan(options.declPackages())) {
            candidates.addAll(translator.translate(type, "Spring 扫描"));
        }
        for (Class<?> type : options.declEnums()) {
            candidates.addAll(translator.translate(type, "显式传入"));
        }
        return candidates;
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
    public Optional<Cache4j<Object, Object>> cache(String name) {
        Cache4j<Object, Object> cached = caches.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!undeclaredEnabled || name == null) {
            return Optional.empty();
        }
        synchronized (caches) {
            Cache4j<Object, Object> again = caches.get(name);
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
    private Cache4j<Object, Object> createUndeclared(String name) {
        undeclared.add(name);
        LOG.warn("[{}]-{}-{}", "缓存装配", "声明", name + " 未经 CacheStrategyDecl 声明，按内建默认创建，请补充到声明枚举"
                        + "（未声明 namespace 累计 " + undeclared.size() + " 个）");
        CacheDefinition definition = BuiltinDefaults.definitionOf(name, LocalStrategy.STATIC);
        CentralCache<Object> central = undeclaredCentralProvider.create(definition.central());
        LocalCache<Object> local = localProvider.create(definition.local(), coordinator,
                central::revisions, definition.central().failOpen());
        Cache4j<Object, Object> created = Cache4jImpl.<Object, Object>builder(name)
                .keyEncoder(keyEncoder)
                .local(local)
                .central(central)
                .hotKeyCoordinator(coordinator)
                .localNullTtlDivisor(definition.localNullTtlDivisor())
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
    public Collection<Cache4j<Object, Object>> caches() {
        return caches.values();
    }

    @Override
    public void close() {
        LOG.debug("[{}]-{}-{}", "缓存装配", "关闭", "关闭 " + caches.size() + " 个缓存"
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
