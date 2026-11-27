package com.asialjim.cache4j.local;

import com.asialjim.cache4j.Cache4jKeyVersion;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.strategy.CacheStrategy;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@RequiredArgsConstructor
public abstract class AbstractVersionedLocalCache4j<V> implements VersionedLocalCache4j<V> {
    // size 最大与进程的缓存块数量一致
    private final Map<String, Integer> strategyVersionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> reconcileTimeMap = new ConcurrentHashMap<>();

    // 过期需要清理
    private final Map<String, Map<Integer, BoundedSet4j<String>>> partitionMap = new ConcurrentHashMap<>();
    private final Map<String, BoundedMap4j<String, Long>> strategyKeyVersionMap = new ConcurrentHashMap<>();

    private BoundedMap4j<String, Long> newVersionMap(int capacity) {
        return BoundedMap4j.lru(capacity);
    }

    private BoundedSet4j<String> newBoundedKeySet(int capacity) {
        return BoundedSet4j.lru(capacity);
    }

    /**
     * 基于中央缓存的版本探测器
     */
    protected final Cache4jKeyVersionDetector centralCache4jKeyVersionDetector;

    @Override
    public final Cache4jKeyVersion keyVersion(String key, CacheStrategy strategy) {
        // 对账
        reconcileIfAbsent(key, strategy);
        return Cache4jKeyVersion.defaultValueOf(key, () -> version(key, strategy));
    }


    /**
     * 获取本地版本号
     */
    protected Long version(String key, CacheStrategy strategy) {
        BoundedMap4j<String, Long> versionMap = strategyKeyVersionMap.get(strategy.namespace());
        // 整个缓存块的版本表都没有
        if (Objects.isNull(versionMap))
            return 0L;
        Long l = versionMap.get(key);
        return optOrElse(l,0L);
    }

    /**
     * @param strategy 策略
     * @return 下一次对账时刻（毫秒）；{@code null} 表示尚未设点
     */
    private Long nextReconcileTime(CacheStrategy strategy) {
        return reconcileTimeMap.get(strategy.namespace());
    }

    /**
     * 判定是否需要对账:判定依据：根据缓存策略计算的时间信号量
     */
    protected boolean reconcileNeed(CacheStrategy strategy) {
        String namespace = strategy.namespace();
        Long next = nextReconcileTime(strategy);
        long now = System.currentTimeMillis();
        if (next == null) {
            // 首次只设点，不做 I/O：冷启动的第一个请求不该为了对账再打一次中央。
            // 代价是第一次对账延后一个周期，换来的是"启动风暴"时没有额外的读放大。
            reconcileTimeMap.put(namespace, now + strategy.reconcileTimeIntervalMillis());
            return false;
        }
        if (now < next) {
            return false;
        }
        reconcileTimeMap.put(namespace, now + strategy.reconcileTimeIntervalMillis());
        return true;
    }

    private static <T> T optOrElse(T t, T other){
        return Objects.nonNull(t) ? t : other;
    }

    private static  <T> T optOrElse(Supplier<T> supplier, T other){
        if (Objects.isNull(supplier))
            return other;
        T t = supplier.get();
        return optOrElse(t,other);
    }

    private BoundedMap4j<String, Long> keyVersionMap(CacheStrategy strategy) {
        int intExact = Math.toIntExact(optOrElse(strategy.localMaxSize(),1_000L));
        return strategyKeyVersionMap.computeIfAbsent(strategy.namespace(), s -> this.newVersionMap(intExact));
    }

    private void reconcileIfAbsent(String key, CacheStrategy strategy) {
        partitionPopulate(key, strategy);
        // 需要对账
        if (reconcileNeed(strategy))
            // 对账
            reconcile(key, strategy);
    }

    /**
     * 本地缓存过期[单独过期]
     * 本地缓存过期监听器调用
     */
    protected void whenLocalCacheExpired(String key, CacheStrategy strategy) {
        whenLocalCacheExpired(Set.of(key), strategy);
    }

    /**
     * 本地缓存过期[批量过期]
     * 本地缓存过期监听器调用
     */
    protected void whenLocalCacheExpired(Set<String> keys, CacheStrategy strategy) {
        Map<Integer, BoundedSet4j<String>> map = this.partitionMap.get(strategy.namespace());
        if (Objects.nonNull(map)) {
            map.forEach((k, v) -> keys.forEach(v::remove));
        }

        // 与上面的 partitionMap 一致：用 get + 判空，而不是 computeIfAbsent —
        // 这里只是清理，不该为了删一条不存在的记录而给该 namespace 造一张空表。
        // 之前这里漏了判空：TTL 过期由 Caffeine 的 removalListener 触发时，
        // 尚未记录过版本的 namespace 会让 versionMap 为 null，直接 NPE。
        BoundedMap4j<String, Long> versionMap = strategyKeyVersionMap.get(strategy.namespace());
        for (String key : keys) {
            if (versionMap != null) {
                versionMap.remove(key);
            }
            delete(key, strategy);
        }
    }

    protected abstract void delete(String key, CacheStrategy strategy);

    private static int partitionSetCapacity(CacheStrategy strategy) {
        long maxSize = optOrElse(strategy.localMaxSize(),1_000L);
        int i = optOrElse(strategy.reconcilePartitions(),16);
        return Math.toIntExact((maxSize / i) + 1);
    }

    /**
     * key 的分区编号计算
     * 需要保证分区一致性：只要key是固定的，那么计算出来的结果就是一致的
     */
    protected final int partitionCode(String key, CacheStrategy strategy) {
        //  对账分区数；单位=个。
        int reconcilePartitions = optOrElse(strategy.reconcilePartitions(),16);
        return Math.floorMod(key.hashCode(), reconcilePartitions);
    }

    private void partitionPopulate(String key, CacheStrategy strategy) {
        int capacity = partitionSetCapacity(strategy);
        Map<Integer, BoundedSet4j<String>> map = strategyPartitionMap(strategy);
        int partition = partitionCode(key, strategy);
        BoundedSet4j<String> original = map.computeIfAbsent(partition, integer -> this.newBoundedKeySet(capacity));
        original.add(key);
        //strategyPartitionSet(key, strategy).add(key);
    }

    private Map<Integer, BoundedSet4j<String>> strategyPartitionMap(CacheStrategy strategy) {
        // 版本号
        Integer version = strategyVersionMap.computeIfAbsent(strategy.namespace(), s -> getCacheStrategy().version().get());
        Integer nowVersion = strategy.version().get();
        // 缓存策略版本号不一致，清理对应策略的分区信息
        if (!Objects.equals(version, nowVersion))
            partitionMap.remove(strategy.namespace());

        return partitionMap.computeIfAbsent(strategy.namespace(), stg -> new ConcurrentHashMap<>());
    }

    /**
     * 根据正在访问的key,计算并搜集本次需要对账的key集合
     */
    private Set<String> strategyPartitionSet(String key, CacheStrategy strategy) {
        // 批量对账个数
        int reconcileBatchSize = Optional.of(strategy).map(CacheStrategy::reconcileBatchSize).map(Supplier::get).orElse(32);
        Map<Integer, BoundedSet4j<String>> strategyPartitionMap = strategyPartitionMap(strategy);
        int strategyPartitionKeySize = strategyPartitionKeySize(strategyPartitionMap);
        if (strategyPartitionKeySize <= reconcileBatchSize)
            return allStrategyPartitionKey(strategyPartitionMap);

        int capacity = partitionSetCapacity(strategy);
        BoundedSet4j<String> original = strategyPartitionMap(strategy).computeIfAbsent(partitionCode(key, strategy), integer -> this.newBoundedKeySet(capacity));
        Set<String> collection = new HashSet<>();

        int i = Math.floorMod(key.hashCode(), reconcileBatchSize);
        for (String s : original.snapshot()) {
            int k = Math.floorMod(s.hashCode(), reconcileBatchSize);
            if (i == k)
                collection.add(s);
        }
        collection.add(key);
        return collection;
    }

    private int strategyPartitionKeySize(Map<Integer, BoundedSet4j<String>> map) {
        AtomicInteger count = new AtomicInteger(0);
        map.forEach((k, v) -> count.addAndGet(v.size()));
        return count.get();
    }

    private Set<String> allStrategyPartitionKey(Map<Integer, BoundedSet4j<String>> map) {
        Set<String> set = new HashSet<>();
        map.forEach((k, v) -> set.addAll(v.snapshot()));
        return set;
    }

    /**
     * 对账:对账同时，需要更新key的版本号
     */
    protected void reconcile(String key, CacheStrategy strategy) {
        // 根据key计算需要对账那些key
        Set<String> keys = strategyPartitionSet(key, strategy);
        try {
            // 分区版本号
            Map<String, Long> partitionVersionMap = this.centralCache4jKeyVersionDetector.partitionVersion(keys, strategy);
            // 执行对账
            doReconcile(partitionVersionMap, keys, strategy);
        } catch (RuntimeException e) {
            // 对账失败：把下一次对账推后到退避时刻，而不是等下一个正常周期。
            // 中央故障期间若每个读都重试，一次故障会被放大成流量风暴。
            postponeReconcile(strategy);
            throw e;
        }
    }

    /**
     * 失败后把下一次对账推后到 {@code versionRetryBackoff} 之后。
     */
    private void postponeReconcile(CacheStrategy strategy) {
        reconcileTimeMap.put(strategy.namespace(),
                System.currentTimeMillis() + strategy.reconcileRetryBackoffMillis());
    }

    /**
     * 执行对账
     * 将中央缓存获取到的版本号，与本地版本号对账，并进行替换
     *
     * @param partitionVersionMap 中央缓存分区版本号集合
     * @param keys                需要对账的key
     * @param strategy            缓存策略
     */
    protected void doReconcile(Map<String, Long> partitionVersionMap, Set<String> keys, CacheStrategy strategy) {
        // 本地保存的版本号
        BoundedMap4j<String, Long> stringIntegerMap = keyVersionMap(strategy);
        stringIntegerMap.putAll(partitionVersionMap);

        // 遍历需要对账的key
        for (String key : keys) {
            // 中央缓存有该key的版本号
            if (partitionVersionMap.containsKey(key)) {
                continue;
            }

            // 中央缓存没有该key的版本号，主动删除
            whenLocalCacheExpired(key, strategy);
        }
    }

}
