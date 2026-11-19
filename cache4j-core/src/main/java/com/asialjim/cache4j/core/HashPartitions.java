package com.asialjim.cache4j.core;

/**
 * key 分区：用 {@code spread(hashCode) & (partitions - 1)} 把 key 稳定地映射到 {@code [0, N)}。
 *
 * <p>用途（同一套算法、两处复用，概念统一）：</p>
 * <ul>
 *   <li><b>热 key 上报</b>：一次访问只上报「当前 key 所属分区」的已热 + 预选 key，
 *       载荷从"全部热 key"降到 {@code maxHotKeys / N}；</li>
 *   <li><b>版本对账</b>：一次对账只校验「当前 key 所属分区」的本地副本，
 *       载荷从"本地全部 key"降到 {@code 本地条目数 / N}。</li>
 * </ul>
 *
 * <p>为什么用<strong>当前被访问的 key</strong>而不是游标轮转：任何 key 被读时，
 * 它自己一定落在被选中的分区里，因此**每个被读的 key 的失效上界仍然是
 * {@code version.checkInterval}**，不需要靠轮转来"轮到它"。冷分区无访问时不必对账
 * （那些副本没人在用）。</p>
 *
 * <p>{@code partitions} 必须是 2 的幂；为 {@code 1} 时退化为"不分区"（等价于全量处理）。</p>
 *
 * @author Asial Jim
 */
public final class HashPartitions {

    private HashPartitions() {
    }

    /**
     * 计算分区号。
     *
     * @param key        逻辑 key
     * @param partitions 分区数（2 的幂，≥ 1）
     * @return 分区号，范围 {@code [0, partitions)}
     */
    public static int of(CacheKey key, int partitions) {
        if (partitions <= 1) {
            return 0;
        }
        int hash = key.logical().hashCode();
        int spread = hash ^ (hash >>> 16);
        return spread & (partitions - 1);
    }

    /**
     * @param partitions 分区数
     * @return 是否为合法的分区数（2 的幂）
     */
    public static boolean isValid(int partitions) {
        return partitions > 0 && (partitions & (partitions - 1)) == 0;
    }
}
