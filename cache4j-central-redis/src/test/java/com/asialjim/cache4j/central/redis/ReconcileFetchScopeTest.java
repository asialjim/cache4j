package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.twolevel.TwoLevelCache;
import com.asialjim.cache4j.local.LocalCaches;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("一次对账把本地全部 key 的版本一起取回（单次 HMGET）")
    void reconcileFetchesAllLocalKeys() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        TwoLevelCache<String, String> cache = cache(store, 1_000);

        // 本地放 3 个 key
        cache.put("a", "va");
        cache.put("b", "vb");
        cache.put("c", "vc");
        assertEquals(3, cache.local().keys().size());

        // 第一次访问只设检查点，不做 I/O
        cache.lookup("a");
        assertEquals(0, store.revisionsCalls(), "首次访问只设点，不应对账");

        // 过检查点后的下一次访问触发一次对账
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();
        cache.lookup("a");

        assertEquals(1, store.revisionsCalls(), "一次对账只应发一次批量取版本");
        assertEquals(3, store.revisionsKeys(),
                "必须取回本地全部 3 个 key 的版本，而不是只取当前访问的 key");
    }

    @Test
    @DisplayName("本地 key 超过批大小时分批取回，但覆盖全部 key")
    void reconcileFetchesAllKeysInBatches() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        // reconcileBatchSize = 2，本地放 5 个 key → 3 批（2 + 2 + 1）
        TwoLevelCache<String, String> cache = cache(store, 2);

        for (String id : new String[]{"a", "b", "c", "d", "e"}) {
            cache.put(id, "v" + id);
        }
        assertEquals(5, cache.local().keys().size());

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
        TwoLevelCache<String, String> cache = cache(store, 1_000);

        cache.put("a", "va");
        cache.lookup("a");
        clock.advance(Duration.ofMillis(200));

        long centralHitsBefore = cache.stats().centralHitCount();
        cache.lookup("a");            // 命中本地，只触发对账

        assertTrue(cache.stats().localHitCount() >= 1L, "应命中本地");
        assertEquals(centralHitsBefore, cache.stats().centralHitCount(), "对账不应产生中央数据读");
        assertEquals(1, store.revisionsCalls(), "对账本身仍取了一次版本");
    }

    @Test
    @DisplayName("对账触发且本地命中：只有对账的 I/O，没有第二次读")
    void reconcileThenHitCostsOneIoGroup() {
        InMemoryValueStore<Object> store = new InMemoryValueStore<>(namespace -> null, clock::millis);
        TwoLevelCache<String, String> cache = cache(store, 1_000);

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
        TwoLevelCache<String, String> cache = cache(store, 1_000);

        cache.put("a", "va");
        cache.put("b", "vb");
        cache.lookup("a");                       // 设检查点
        // 让 c 在本地缺席但在中央存在：模拟"本地没这条副本"
        cache.central().putAndGetRevision(
                new CacheKey(NS, "s1:c"), "vc", Duration.ofMinutes(10));
        clock.advance(Duration.ofMillis(200));
        store.resetCounters();

        assertEquals("vc", cache.lookup("c").optional().orElseThrow());

        assertEquals(1, store.revisionsCalls(), "对账取版本：1 次（本地 2 个 key）");
        assertEquals(2, store.revisionsKeys(), "对账覆盖本地已有的 a、b（c 不在本地）");
        assertEquals(1, store.readCalls(), "本地未命中 → 再读一次（版本 + 值）");
    }

    // ------------------------------------------------------------------ 辅助

    @SuppressWarnings("unchecked")
    private TwoLevelCache<String, String> cache(InMemoryValueStore<Object> store, int batchSize) {
        LocalCacheSpec localSpec = LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1),
                new LocalCacheSpec.VersionSpec(
                        Duration.ofMillis(100), 0D, Duration.ofMillis(50), batchSize));
        LocalCache<String> local = LocalCaches.create(localSpec, null, clock::nanos);
        CentralCache<String> central = new RedisCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).build(),
                (RedisValueStore<String>) (RedisValueStore<?>) store);
        return TwoLevelCache.<String, String>builder(NS)
                .local(local)
                .central(central)
                .nanoClock(clock::nanos)
                .milliClock(clock::millis)
                .build();
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
