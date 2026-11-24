package com.asialjim.cache4j.core.coordination;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.Lookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 进程内的单飞实现：用 {@link CompletableFuture} 表合并同一 key 的并发加载。
 *
 * <p>只做进程内合并；跨节点保护需要分布式锁实现（同一个 {@link SingleFlight} 接口）。</p>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class LocalSingleFlight<K, V> implements SingleFlight<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSingleFlight.class);

    private final ConcurrentHashMap<K, CompletableFuture<Lookup<V>>> inflight = new ConcurrentHashMap<>();

    @Override
    public Lookup<V> once(K key, Supplier<Lookup<V>> loader) {
        CompletableFuture<Lookup<V>> mine = new CompletableFuture<>();
        CompletableFuture<Lookup<V>> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            // 合并到别人的加载：同样要有结论，否则请求数与命中/未命中数不平账
            return await(existing);
        }
        try {
            Lookup<V> loaded = loader.get();
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            // 异常不缓存：让本次所有等待者收到同一异常，下次重新尝试
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    @Override
    public void close() {
        inflight.forEach((key, future) ->
                future.completeExceptionally(new CacheException(CacheResCode.UNKNOWN, "缓存已关闭")));
        inflight.clear();
    }

    /**
     * @return 进行中的加载数（排障用）
     */
    int inflightCount() {
        return inflight.size();
    }

    private static <V> Lookup<V> await(CompletableFuture<Lookup<V>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
