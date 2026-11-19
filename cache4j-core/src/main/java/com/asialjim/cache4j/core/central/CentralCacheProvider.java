package com.asialjim.cache4j.core.central;

import com.asialjim.cache4j.core.CacheCodec;

import java.util.Optional;

/**
 * 中央缓存工厂：把一个存储实现装配成 {@link CentralCache} 视图。
 *
 * @author Asial Jim
 */
public interface CentralCacheProvider extends AutoCloseable {

    /**
     * 创建一条命名缓存的视图。
     *
     * @param spec  配置
     * @param codec 值编解码器
     * @param <V>   值类型
     * @return 中央缓存视图
     */
    <V> CentralCache<V> create(CentralCacheSpec spec, CacheCodec<V> codec);

    /**
     * 可选：提供跨节点热 key 协调；为空时 {@code DYNAMIC + coordination=GLOBAL} <strong>启动失败</strong>。
     *
     * @return 协调器
     */
    default Optional<HotKeyCoordinator> hotKeyCoordinator() {
        return Optional.empty();
    }

    /**
     * 关闭 provider 持有的连接与资源；必须幂等。
     */
    @Override
    void close();
}
