package com.asialjim.cache4j.central;

import com.asialjim.cache4j.Cache4jKeyVersion;
import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 中央版本探测的骨架：把"批量核对版本"的<strong>分批协议</strong>固化下来，子类只实现取数。
 *
 * <p>本地侧（{@code AbstractVersionedLocalCache4j}）已经按 {@code reconcilePartitions}
 * 挑出了"这一次要核对这个分区里的哪些 key"，本类负责把这一批再按
 * {@code reconcileBatchSize} 拆成若干次取数，然后把结果合并。</p>
 *
 * <h2>为什么必须分批</h2>
 * <p>一次要核对的 key 数由本地容量决定（{@code localMaxSize} 除以分区数），
 * 而单次取数的规模有独立上限：请求体太大、响应包太大、超时边界都会跟着放大。
 * 分批让"要核对的量"和"单次请求的量"解耦。</p>
 *
 * <h2>失败语义：<strong>必须抛，不能吞</strong></h2>
 * <p>返回的 Map 里<strong>缺少某个 key 就代表"中央没有这个 key"，本地会据此删掉副本</strong>
 * （见 {@code AbstractVersionedLocalCache4j#doReconcile}）。所以取数失败时
 * 绝不能返回空 Map —— 那等于把本地缓存整个清空。异常必须原样抛出，
 * 交由调用方进入 {@code versionRetryBackoff} 退避。</p>
 *
 * @author Asial Jim
 */
public abstract class AbstractCentralKeyVersionDetector4j implements Cache4jKeyVersionDetector {

    /**
     * 单 key 版本。
     *
     * <p>走批量原语而不是单独开一个单 key 原语：多节点的对账几乎总是批量的，
     * 单 key 版本只用于装配/自检，不值得为它多定义一个存储交互。</p>
     */
    @Override
    public final Cache4jKeyVersion keyVersion(String key, CacheStrategy strategy) {
        Map<String, Long> versions = readVersions(Set.of(key), strategy);
        Long version = versions == null ? null : versions.get(key);
        return Cache4jKeyVersion.defaultValueOf(key, () -> version);
    }

    /**
     * 批量版本。
     *
     * @param keys     需要核对的 key（本地侧已按分区挑好）
     * @param strategy 策略
     * @return key → 中央版本；<strong>缺失的 key 表示中央不存在</strong>
     */
    @Override
    public final Map<String, Long> partitionVersion(Set<String> keys, CacheStrategy strategy) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        int batchSize = Math.max(1, intOf(strategy.reconcileBatchSize(), 512));
        if (keys.size() <= batchSize) {
            Map<String, Long> single = readVersions(keys, strategy);
            return single == null ? Map.of() : single;
        }

        Map<String, Long> merged = new HashMap<>(Math.min(keys.size() * 2, 4096));
        for (List<String> batch : partition(keys, batchSize)) {
            Map<String, Long> part = readVersions(Set.copyOf(batch), strategy);
            if (part != null) {
                merged.putAll(part);
            }
        }
        return merged;
    }

    /**
     * 取一批 key 的中央版本。
     *
     * <p>实现方应当只做"取数"，不要在这里做降级：失败就抛出，
     * 由调用方的退避机制处理。</p>
     *
     * @param keys     key 集合（已按批量上限切好）
     * @param strategy 策略
     * @return key → 版本；中央不存在的 key 不要放进结果
     */
    protected abstract Map<String, Long> readVersions(Set<String> keys, CacheStrategy strategy);

    // ------------------------------------------------------------------ 工具

    private static List<List<String>> partition(Set<String> keys, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>(Math.min(keys.size(), batchSize));
        for (String key : keys) {
            if (current.size() >= batchSize) {
                batches.add(current);
                current = new ArrayList<>(batchSize);
            }
            current.add(key);
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static int intOf(Supplier<Integer> supplier, int fallback) {
        return Optional.ofNullable(supplier).map(Supplier::get).orElse(fallback);
    }
}
