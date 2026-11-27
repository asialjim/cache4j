package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.KeyEncoder;
import com.asialjim.cache4j.level.Cache4jImpl;
import com.asialjim.cache4j.level.CentralCache4j;
import com.asialjim.cache4j.level.DynamicLocalCache4j;
import com.asialjim.cache4j.level.StaticLocalCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;
import com.asialjim.cache4j.strategy.CacheStrategyReader;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.NonNull;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring {@link CacheManager} 实现：把 {@code @Cacheable(cacheNames = "xxx")} 里的名字
 * 映射到一个 {@link Cache4jCache}。
 *
 * <h2>按需创建，而不是预注册</h2>
 * <p>{@code cache4j-2} 的缓存门面需要绑定一份 {@code CacheStrategy}，
 * 所以实例只能由使用方的给出——本模块不知道也不该知道
 * 本地/中央怎么装。第一次 {@code getCache(name)} 时创建，之后复用。</p>
 *
 * <h2>两种模式</h2>
 * <table border="1">
 *   <caption>{@code allowDynamicCreation}</caption>
 *   <tr><th>模式</th><th>行为</th><th>适用</th></tr>
 *   <tr><td>{@code true}</td>
 *       <td>任何名字都创建，{@code getCache} 永不返回 {@code null}</td>
 *       <td>策略由 {@code CacheStrategyReader} 按需读取时</td></tr>
 *   <tr><td>{@code false}</td>
 *       <td>只返回已创建过的；未知名字返回 {@code null}</td>
 *       <td>要求 cacheName 必须先声明的场景（能在启动期发现拼错）</td></tr>
 * </table>
 *
 * <p>默认 {@code true}：{@code cache4j} 的 {@code CacheBlock} 枚举本身就是声明源，
 * 但使用者可能只想要"用到即创建"的简单形态，不该被强制先注册一遍。</p>
 *
 * @author Asial Jim
 */
public final class Cache4jCacheManager implements CacheManager {
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    private final CacheStrategyReader cacheStrategyReader;
    private final boolean allowDynamicCreation;
    private final KeyEncoder keyEncoder;
    private final StaticLocalCache4j.Provider staticLocalCacheProvider;
    private final DynamicLocalCache4j.Provider dynamicLocalCacheProvider;
    private final CentralCache4j.Provider centralCache4jProvider;

    public Cache4jCacheManager(
            boolean allowDynamicCreation,
            KeyEncoder keyEncoder,
            CacheStrategyReader cacheStrategyReader,
            StaticLocalCache4j.Provider staticLocalCacheProvider,
            DynamicLocalCache4j.Provider dynamicLocalCacheProvider,
            CentralCache4j.Provider centralCache4jProvider
    ) {
        this.cacheStrategyReader = cacheStrategyReader;
        this.allowDynamicCreation = allowDynamicCreation;
        this.keyEncoder = keyEncoder;
        this.staticLocalCacheProvider = staticLocalCacheProvider;
        this.dynamicLocalCacheProvider = dynamicLocalCacheProvider;
        this.centralCache4jProvider = centralCache4jProvider;
    }


    @Override
    @SuppressWarnings("NullableProblems")
    public Cache getCache(String name) {
        Cache existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        if (!allowDynamicCreation) {
            return null;
        }
        return doGetCache(name);
    }

    @NonNull
    private Cache doGetCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            CacheStrategy cacheStrategy = this.cacheStrategyReader.namespaceOf(n);
            Cache4jImpl<Object, Object> cache = new Cache4jImpl<>(cacheStrategy, keyEncoder, staticLocalCacheProvider, dynamicLocalCacheProvider, centralCache4jProvider);
            return new Cache4jCache(cache);
        });
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }

    /**
     * 预注册一个缓存。
     *
     * <p>用于 {@code allowDynamicCreation=false} 的模式：先声明、后使用，
     * 拼错 cacheName 时会在运行期拿到 {@code null} 而不是悄悄建出一个空缓存。</p>
     *
     * @param name 缓存名
     * @return 注册后的缓存
     */
    public Cache register(String name) {
        return doGetCache(name);
    }

    /**
     * 移除并返回一个已注册的缓存；不影响中央已存的数据。
     *
     * @param name 缓存名
     * @return 被移除的缓存；不存在返回 {@code null}
     */
    public Cache unregister(String name) {
        return caches.remove(name);
    }
}
