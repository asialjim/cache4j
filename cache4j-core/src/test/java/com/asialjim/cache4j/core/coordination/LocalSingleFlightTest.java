package com.asialjim.cache4j.core.coordination;

import com.asialjim.cache4j.core.Lookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单飞契约。
 *
 * @author Asial Jim
 */
class LocalSingleFlightTest {

    private final SingleFlight<String, String> singleFlight = new LocalSingleFlight<>();

    @Test
    @DisplayName("同 key 并发只执行一次加载，其余复用结果")
    void coalescesConcurrentLoads() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    Lookup<String> result = singleFlight.once("k", () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Lookup.hit("v");
                    });
                    assertEquals("v", result.optional().orElseThrow());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, loads.get(), "同 key 的并发加载应合并成一次");
    }

    @Test
    @DisplayName("不同 key 完全并行，互不阻塞")
    void distinctKeysRunInParallel() {
        assertEquals("v1", singleFlight.once("a", () -> Lookup.hit("v1")).optional().orElseThrow());
        assertEquals("v2", singleFlight.once("b", () -> Lookup.hit("v2")).optional().orElseThrow());
    }

    @Test
    @DisplayName("负结果同样被合并；加载结束后再调用会重新加载")
    void coalescesNegativeResults() {
        AtomicInteger loads = new AtomicInteger();
        Lookup<String> first = singleFlight.once("k", () -> {
            loads.incrementAndGet();
            return Lookup.negative();
        });
        Lookup<String> second = singleFlight.once("k", () -> {
            loads.incrementAndGet();
            return Lookup.hit("v");
        });
        assertTrue(first.isNegative());
        assertEquals("v", second.optional().orElseThrow(), "上一次已结束，这次应重新加载");
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("异常不缓存：本次抛出，下次重新尝试")
    void exceptionsAreNotCached() {
        assertThrows(IllegalStateException.class,
                () -> singleFlight.once("k", () -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("v", singleFlight.once("k", () -> Lookup.hit("v")).optional().orElseThrow());
    }
}
