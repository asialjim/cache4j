package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.VersionSpec;

import com.asialjim.cache4j.core.twolevel.Cache4jImpl;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.HashPartitions;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.VersionedLocalCache;
import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.local.LocalCaches;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本对账的取版本范围：一次对账必须拿回<strong>该命名空间下本地已知的全部 key</strong>的版本，
 * 而不是只拿当前访问的那个 key。
 *
 * @author Asial Jim
 */
class ReconcileFetchScopeTest {

    private static final String NS = "user:detail";

    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("本地条目 ≤ 批大小：直接全量对账（一次 HMGET 装得下）")
    void notLargerThanBatchMeansWholeScope() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        Cache4j<String, String> cache = cache(store, 1_000, 16);   // 2 条 ≤ 批大小 1000

        cache.put("a", "va");
        cache.put("b", "vb");
        assertEquals(2, ((VersionedLocalCache<?>) cache.local()).keys().size());

        cache.lookup("a");                       // 只设检查点
        assertEquals(0, store.revisionsCalls(), "首次访问只设点，不应对账");

        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");

        assertEquals(1, store.revisionsCalls(), "全量也只需一次批量取版本");
        assertEquals(2, store.revisionsKeys(), "装得下一批 → 全部本地 key 一起对账");
    }

    @Test
    @DisplayName("本地条目 > 批大小：按同模 key 分区，只对账与访问 key 同模的那些")
    void largerThanBatchMeansModuloPartition() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        // 批大小 1、分区 2：本地 2 条 > 批大小 1 → 进入分区模式
        Cache4j<String, String> cache = cache(store, 1, 2);

        String[] pair = twoKeysInDifferentPartitions(2);
        String visitor = pair[0];
        String other = pair[1];
        cache.put(visitor, "v-" + visitor);
        cache.put(other, "v-" + other);
        assertEquals(2, ((VersionedLocalCache<?>) cache.local()).keys().size());

        cache.lookup(visitor);
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup(visitor);

        assertEquals(1, store.revisionsKeys(),
                "只对账与访问 key 同模的那 1 个；不同模的 " + other + " 本次不参与");
    }

    @Test
    @DisplayName("本地条目远超一批时才分片：一次对账只有 1 批，载荷约为 1/分区数")
    void partitionsReducePayload() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        // 批大小 10 + 16 分区；maxSize 放大到 200 避免容量淘汰干扰载荷断言
        Cache4j<String, String> cache = cache(store, 10, 16, 200, Duration.ofMinutes(1));

        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, "v" + i);
        }
        // 本地 maxSize=100 且分段实现会略超发/淘汰，取实际条目数做基准
        int localSize = ((VersionedLocalCache<?>) cache.local()).keys().size();
        assertTrue(localSize > 50, "本地应装入相当数量的条目，实际=" + localSize);

        cache.lookup("k0");
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("k0");

        int partitions = 16;
        long expected = expectedPartitionPayload(cache, "k0", partitions);
        assertEquals(1, store.revisionsCalls(), "分片后每批 ≤ 批大小 → 一次对账只需 1 次往返");
        assertEquals(expected, store.revisionsKeys());
        assertTrue(partitions > 1, "应按配置分区：N=" + partitions);
        assertTrue(expected * 2 < localSize,
                "单次对账载荷应明显小于本地总条目数：本地=" + localSize + "，本次=" + expected);
    }

    @Test
    @DisplayName("本地 key 超过批大小时分批取回，但覆盖全部 key")
    void reconcileFetchesAllKeysInBatches() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        // reconcileBatchSize = 2，本地放 5 个 key → 3 批（2 + 2 + 1）
        Cache4j<String, String> cache = cache(store, 2, 1);   // 分区=1 退化：等价全量

        for (String id : new String[]{"a", "b", "c", "d", "e"}) {
            cache.put(id, "v" + id);
        }
        assertEquals(5, ((VersionedLocalCache<?>) cache.local()).keys().size());

        cache.lookup("a");
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");

        assertEquals(3, store.revisionsCalls(), "2+2+1 应为 3 次批量取版本");
        assertEquals(5, store.revisionsKeys(), "5 个 key 必须全部覆盖");
    }

    @Test
    @DisplayName("对账只取版本、不取数据值（不会读到 data key）")
    void reconcileDoesNotFetchValues() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        Cache4j<String, String> cache = cache(store, 1_000);

        cache.put("a", "va");
        cache.lookup("a");
        clock.advance(Duration.ofMillis(200));

        int readsBefore = store.readCalls();
        cache.lookup("a");            // 命中本地，只触发对账

        assertEquals(readsBefore, store.readCalls(), "对账不应产生中央数据读（读由本地命中吸收）");
        assertEquals(1, store.revisionsCalls(), "对账本身仍取了一次版本");
    }

    @Test
    @DisplayName("对账触发且本地命中：只有对账的 I/O，没有第二次读")
    void reconcileThenHitCostsOneIoGroup() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        Cache4j<String, String> cache = cache(store, 1_000);

        cache.put("a", "va");
        cache.lookup("a");                       // 设检查点
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");                       // 到点：先对账，再命中本地

        assertEquals(1, store.revisionsCalls(), "对账取版本：1 次");
        assertEquals(0, store.readCalls(), "本地命中，不应再读中央");
    }

    @Test
    @DisplayName("对账触发且本地未命中：对账 + 读 = 两次 I/O")
    void reconcileThenMissCostsTwoIoGroups() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        Cache4j<String, String> cache = cache(store, 1_000);

        cache.put("a", "va");
        cache.put("b", "vb");
        cache.lookup("a");                       // 设检查点
        // 让 c 在本地缺席但在中央存在：模拟"本地没这条副本"
        cache.central().putAndGetRevision(
                new CacheKey(NS, "c"), "vc", Duration.ofMinutes(10));
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();

        assertEquals("vc", cache.lookup("c").optional().orElseThrow());

        assertEquals(1, store.revisionsCalls(), "对账取版本：1 次（本地 2 个 key）");
        assertEquals(2, store.revisionsKeys(), "对账覆盖本地已有的 a、b（c 不在本地）");
        assertEquals(1, store.readCalls(), "本地未命中 → 再读一次（版本 + 值）");
    }

    @Test
    @DisplayName("存储层自行过期的条目：下一轮对账把索引里的幽灵条目剔除，之后不再产生 I/O")
    void ghostEntriesArePrunedByNextReconcile() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        // 本地 TTL 1s；maxSize 足够大，排除容量淘汰的干扰
        Cache4j<String, String> cache = cache(store, 1_000, 16, 200, Duration.ofSeconds(1));

        cache.put("a", "va");
        cache.put("b", "vb");
        assertEquals(2, ((VersionedLocalCache<?>) cache.local()).keys().size());

        cache.lookup("a");                          // 设检查点
        clock.advance(Duration.ofMillis(200));

        // 让本地副本自然过期（索引里暂时还留着这两个 key）
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, ((VersionedLocalCache<?>) cache.local()).keys().size(), "本地副本应已过期");

        store.resetCounters();
        cache.lookup("a");                          // 第一次对账：顺手剔除幽灵条目
        assertEquals(1, store.revisionsCalls(), "仍会发一次取版本（此时还带着幽灵条目）");
        assertEquals(2, store.revisionsKeys(),
                "幽灵条目会被查一次版本，随即从索引剔除；只查版本、不读数据值");

        // 再等一个检查点：幽灵已被剔除，索引里只剩刚才回填的 a
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");
        assertEquals(1, store.revisionsCalls());
        assertEquals(1, store.revisionsKeys(),
                "幽灵条目已从索引剔除；这次只对账回填的 a");
    }

    @Test
    @DisplayName("本地条目过期后两张表立即清理：下一次对账不再带上这个 key")
    void expiredEntryIsForgottenImmediately() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        Cache4j<String, String> cache = cache(store, 1_000, 16, 200, Duration.ofSeconds(1));

        cache.put("a", "va");
        cache.put("b", "vb");
        cache.lookup("a");                          // 设检查点
        clock.advance(Duration.ofSeconds(2));       // 两个副本都过期

        // 访问本地触发惰性过期 → 存储层回调 → 分区号与分区索引同步摘除
        assertNull(((VersionedLocalCache<?>) cache.local()).entryOf(new CacheKey(NS, "a")));
        assertNull(((VersionedLocalCache<?>) cache.local()).entryOf(new CacheKey(NS, "b")));

        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");

        assertEquals(0, store.revisionsCalls(),
                "两张表已清空 → 对账时本地无副本，不该再为过期条目发任何取版本请求");
    }

    // ------------------------------------------------------------------ 辅助

    @SuppressWarnings("unchecked")
    private Cache4j<String, String> cache(InMemoryValueStore<Object> store, int batchSize) {
        return cache(store, batchSize, 1);
    }

    private Cache4j<String, String> cache(InMemoryValueStore<Object> store, int batchSize,
                                                int partitions) {
        return cache(store, batchSize, partitions, 100, Duration.ofMinutes(1));
    }

    private Cache4j<String, String> cache(InMemoryValueStore<Object> store, int batchSize,
                                                int partitions, int maxSize, Duration localTtl) {
        LocalCacheSpec localSpec = LocalCacheSpec.staticCache(NS, maxSize, localTtl,
                new VersionSpec(
                        Duration.ofMillis(100), Duration.ZERO, Duration.ofMillis(50), batchSize, partitions));
        CentralCache<String> central = new RedisCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).build().toStrategy(),
                (RedisValueStore<String>) (RedisValueStore<?>) store);
        @SuppressWarnings("unchecked")
        VersionedLocalCache<String> local = (VersionedLocalCache<String>) (VersionedLocalCache)
                LocalCaches.create(localSpec.toStrategy(), null, clock::nanos, central::revisions,
                        central.cacheStrategy().failOpen());
        return Cache4jImpl.<String, String>builder(NS)
                .local(local)
                .central(central)
                .build();
    }

    /**
     * 挑两个落在不同分区的 key（用于验证"只对账同模 key"）。
     *
     * @param partitions 分区数
     * @return 两个明文 key，{@code [0]} 与 {@code [1]} 必然不同模
     */
    private String[] twoKeysInDifferentPartitions(int partitions) {
        String first = null;
        int firstPartition = -1;
        for (int i = 0; i < 64; i++) {
            String candidate = "k" + i;
            int partition = HashPartitions.of(
                    new CacheKey(NS, candidate), partitions);
            if (first == null) {
                first = candidate;
                firstPartition = partition;
            } else if (partition != firstPartition) {
                return new String[]{first, candidate};
            }
        }
        throw new IllegalStateException("未能构造出不同分区的两个 key");
    }

    /**
     * 期望的对账载荷：与给定 key 同分区、且当前在本地缓存里的 key 数。
     *
     * @param cache      门面
     * @param visitor    访问的 key（明文）
     * @param partitions 分区数
     * @return 期望的 field 数
     */
    private long expectedPartitionPayload(Cache4j<String, String> cache, String visitor,
                                          int partitions) {
        int wanted = HashPartitions.of(new CacheKey(NS, visitor),
                partitions);
        return ((VersionedLocalCache<?>) cache.local()).keys().stream()
                .filter(key -> HashPartitions.of(key, partitions) == wanted)
                .count();
    }

    /**
     * 可推进的测试时钟。
     */
    static final class MutableClock {

        private long nanos = 1_700_000_000_000_000_000L;
        private long millis = 1_700_000_000_000L;

        long nanos() {
            return nanos;
        }

        long millis() {
            return millis;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
            millis += duration.toMillis();
        }
    }
}
