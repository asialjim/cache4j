package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.core.twolevel.Cache4jImpl;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.CentralCache;
import com.asialjim.cache4j.core.central.CentralCacheSpec;
import com.asialjim.cache4j.core.central.HotKeyCoordinator;
import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.Cache4j;
import com.asialjim.cache4j.local.LocalCaches;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全栈集成测试：门面 + 本地缓存 + Redis 语义层 + 适配层 store（内存实现，不需要 Redis 容器）。
 *
 * <p>验证两件事：</p>
 * <ol>
 *   <li>跨"节点"（两个门面共享同一个 store）的版本对账闭环；</li>
 *   <li>热 key 的集群口径升热闭环（中央判 HOT → 各节点补偿升热）。</li>
 * </ol>
 *
 * @author Asial Jim
 */
class RedisSemanticIntegrationTest {

    private static final String NS = "user:detail";

    private final MutableClock clock = new MutableClock();

    /**
     * 构建一个"节点"：独立本地缓存 + 共享中央 store。
     *
     * @param store        共享的下行 store
     * @param spec         本地配置
     * @param coordinator  热 key 协调器（可为 {@code null}）
     * @return 门面
     */
    @SuppressWarnings("unchecked")
    private Cache4j<String, String> node(InMemoryValueStore<Object> store,
                                               LocalCacheSpec spec,
                                               HotKeyCoordinator coordinator) {
        LocalCache<String> local = LocalCaches.create(spec, coordinator, clock::nanos);
        CentralCache<String> central = new RedisCentralCache<>(
                CentralCacheSpec.builder(NS).ttl(Duration.ofMinutes(10)).build(),
                (RedisValueStore<String>) (RedisValueStore<?>) store);
        return Cache4jImpl.<String, String>builder(NS)
                .local(local)
                .central(central)
                .nanoClock(clock::nanos)
                .milliClock(clock::millis)
                .build();
    }

    private InMemoryValueStore<Object> store(LocalCacheSpec.GlobalSpec global) {
        return new InMemoryValueStore<>(namespace -> NS.equals(namespace) ? global : null, clock::millis);
    }

    private LocalCacheSpec staticSpec() {
        return LocalCacheSpec.staticCache(NS, 100, Duration.ofMinutes(1),
                new LocalCacheSpec.VersionSpec(Duration.ofMillis(100), Duration.ZERO, Duration.ofMillis(50), 1_000));
    }

    @Test
    @DisplayName("跨节点：A 写中央后，B 的本地副本在下一轮对账被清除并读到新值")
    void reconcileAcrossNodes() {
        InMemoryValueStore<Object> store = store(null);
        Cache4j<String, String> nodeA = node(store, staticSpec(), null);
        Cache4j<String, String> nodeB = node(store, staticSpec(), null);

        nodeA.put("42", "u42");
        assertEquals("u42", nodeB.lookup("42").optional().orElseThrow());
        // B 已回填本地：第二次读不应再打中央
        int readsBefore = store.readCalls();
        assertEquals("u42", nodeB.lookup("42").optional().orElseThrow());
        assertEquals(readsBefore, store.readCalls(), "第二次应命中本地，不读中央");

        // A 改写
        clock.advance(Duration.ofMillis(1));
        nodeA.put("42", "u42-v2");

        // B 未到对账点：仍是旧值
        assertEquals("u42", nodeB.lookup("42").optional().orElseThrow());

        // 到点后对账清除副本，读到新值
        clock.advance(Duration.ofMillis(200));
        assertEquals("u42-v2", nodeB.lookup("42").optional().orElseThrow());
    }

    @Test
    @DisplayName("跨节点：A 失效后，B 的本地副本被清除（无需 tombstone）")
    void remoteEvictAcrossNodes() {
        InMemoryValueStore<Object> store = store(null);
        Cache4j<String, String> nodeA = node(store, staticSpec(), null);
        Cache4j<String, String> nodeB = node(store, staticSpec(), null);

        nodeA.put("42", "u42");
        nodeB.lookup("42");
        nodeA.evict("42");

        clock.advance(Duration.ofMillis(200));
        assertTrue(nodeB.lookup("42").isMiss());
    }

    @Test
    @DisplayName("热 key：中央判 HOT 后，各节点补偿升热（集群口径闭环）")
    void globalPromotionAcrossNodes() {
        // QPS 口径：窗口 1s，全局 2 QPS → hotCount = 2 次；本地 2 QPS × 1s 窗口 = 2 次即升热
        LocalCacheSpec.GlobalSpec global = LocalCacheSpec.GlobalSpec.of(Duration.ofSeconds(1), 2L, 1);
        InMemoryValueStore<Object> store = store(global);
        HotKeyCoordinator coordinator = new RedisHotKeyCoordinator(store,
                namespace -> NS.equals(namespace) ? global : null);

        LocalCacheSpec.DynamicSpec base = LocalCacheSpec.DynamicSpec.defaults();
        LocalCacheSpec.DynamicSpec dynamic = new LocalCacheSpec.DynamicSpec(
                2, 1, Duration.ofSeconds(1), 16, 1_000, base.syncInterval(), base.cooldown(),
                LocalCacheSpec.Coordination.GLOBAL,
                new LocalCacheSpec.GlobalSpec(Duration.ofSeconds(1), 2L, 1L, 3, 3, 1),
                base.sketchWidthBits(), base.sketchDepth(), base.hotReportPartitions(),
                base.hotReportBatchMax(), base.candidateCapacity());
        AtomicReference<LocalCacheSpec> holder = new AtomicReference<>(
                LocalCacheSpec.dynamicCache(NS, 1_000, Duration.ofMinutes(1), dynamic));

        Cache4j<String, String> nodeA = node(store, holder.get(), coordinator);
        nodeA.put("42", "u42");

        // 达到本地升热阈值（2 次）→ 升热；随后到点上报，中央合计达阈值
        for (int i = 0; i < 3; i++) {
            nodeA.lookup("42");
        }
        clock.advance(Duration.ofSeconds(2));
        nodeA.lookup("42");

        assertTrue(nodeA.local().keys().stream().anyMatch(k -> "42".equals(k.encoded())),
                "本地应升热（该 key 进入本地副本）");
    }

    /**
     * 可推进的测试时钟（纳秒 + 毫秒同源）。
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
