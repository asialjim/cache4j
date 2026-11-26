package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.GlobalSpec;

import com.asialjim.cache4j.central.redis.RedisAccessException;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.central.VersionedLookup;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringRedisValueStore} 测试。
 *
 * <p>纯逻辑部分（版本串解析、异常翻译）始终执行；需要真实 Redis 的用例放在
 * {@link WithRedis} 里用 {@link Assumptions#assumeTrue} 门控 —— 本机没有 Redis 时整组跳过，
 * CI 里起一个 Redis 即可跑全。</p>
 *
 * @author Asial Jim
 */
class SpringRedisValueStoreTest {

    private static final String HOST = System.getProperty("cache4j.test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("cache4j.test.redis.port", 6379);
    private static final String NS = "cache4j:test:user";

    @Test
    @DisplayName("版本串解析：\"<rev>|<expireAt>\"，缺失按 0")
    void parseRevision() {
        SpringRedisValueStore.RevAndExpire parsed = SpringRedisValueStore.parseRevision(
                "1700000000000|1700000060000".getBytes(StandardCharsets.UTF_8));
        assertEquals(1_700_000_000_000L, parsed.revision());
        assertEquals(1_700_000_060_000L, parsed.expireAtMillis());

        SpringRedisValueStore.RevAndExpire legacy = SpringRedisValueStore.parseRevision(
                "42".getBytes(StandardCharsets.UTF_8));
        assertEquals(42L, legacy.revision());
        assertEquals(0L, legacy.expireAtMillis());

        assertEquals(0L, SpringRedisValueStore.parseRevision(null).revision());
    }

    @Test
    @DisplayName("异常翻译：连接失败 → UNAVAILABLE，超时 → TIMEOUT，其它 → COMMAND_FAILED")
    void translateSpringExceptions() {
        assertEquals(RedisAccessException.Kind.UNAVAILABLE,
                SpringRedisValueStore.translate(new RedisConnectionFailureException("down")).kind());
        assertEquals(RedisAccessException.Kind.TIMEOUT,
                SpringRedisValueStore.translate(new QueryTimeoutException("slow")).kind());
        assertEquals(RedisAccessException.Kind.COMMAND_FAILED,
                SpringRedisValueStore.translate(
                        new RedisSystemException("OOM", new IllegalStateException("oom"))).kind());
    }

    private static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 需要真实 Redis 的用例。
     */
    @Nested
    class WithRedis {

        private RedisConnectionFactory factory;
        private SpringRedisValueStore<String> store;

        @BeforeEach
        void setUp() {
            Assumptions.assumeTrue(redisAvailable(),
                    () -> "本地 " + HOST + ":" + PORT + " 没有 Redis，跳过集成用例");
            LettuceConnectionFactory lettuce = new LettuceConnectionFactory(HOST, PORT);
            lettuce.afterPropertiesSet();
            this.factory = lettuce;

            // QPS 口径：窗口 10s、10 QPS → hotCount = 100 次（与上报量 120 匹配）
            GlobalSpec global = GlobalSpec.of(Duration.ofSeconds(10), 10L, 1);
            this.store = new SpringRedisValueStore<>(factory, RedisKeyFormatter.defaults(),
                    new StringRedisSerializer(), System::currentTimeMillis)
                    .globalSpecs(namespace -> NS.equals(namespace) ? global : null);
            store.clear(NS);
        }

        @AfterEach
        void tearDown() {
            if (store != null) {
                store.clear(NS);
            }
            if (factory instanceof LettuceConnectionFactory lettuce) {
                lettuce.destroy();
            }
        }

        private CacheKey key(String value) {
            return new CacheKey(NS, "s" + value.length() + ":" + value);
        }

        @Test
        @DisplayName("写后读：版本与值一并返回")
        void writeThenRead() {
            long revision = store.write(key("42"), "u42", Duration.ofMinutes(1));
            assertTrue(revision > 0L);

            VersionedLookup<String> lookup = store.read(key("42"));
            assertTrue(lookup.lookup().isHit());
            assertEquals("u42", lookup.lookup().optional().orElseThrow());
            assertEquals(revision, lookup.revision());
        }

        @Test
        @DisplayName("同一毫秒连续两次写也产生不同版本")
        void revisionsDiffer() {
            long first = store.write(key("42"), "v1", Duration.ofMinutes(1));
            long second = store.write(key("42"), "v2", Duration.ofMinutes(1));
            assertTrue(second > first);
        }

        @Test
        @DisplayName("负副本可读回为 Negative")
        void negativeRoundTrip() {
            store.writeNegative(key("404"), Duration.ofSeconds(30));
            assertTrue(store.read(key("404")).lookup().isNegative());
        }

        @Test
        @DisplayName("删除连版本一起删：revisions 里不再出现")
        void deleteRemovesRevision() {
            store.write(key("42"), "u42", Duration.ofMinutes(1));
            store.delete(key("42"));
            assertEquals(0L, store.revision(key("42")));
            assertTrue(store.revisions(List.of(key("42"))).isEmpty());
        }

        @Test
        @DisplayName("批量取版本只返回仍然存在的 key")
        void revisionsOnlyLiveKeys() {
            store.write(key("a"), "va", Duration.ofMinutes(1));
            store.write(key("b"), "vb", Duration.ofMinutes(1));
            Map<CacheKey, Long> revisions = store.revisions(List.of(key("a"), key("b"), key("c")));
            assertEquals(2, revisions.size());
            assertFalse(revisions.containsKey(key("c")));
        }

        @Test
        @DisplayName("putIfAbsent：已存在返回 empty 且版本不变")
        void writeIfAbsent() {
            long first = store.write(key("42"), "v1", Duration.ofMinutes(1));
            OptionalLong again = store.writeIfAbsent(key("42"), "v2", Duration.ofMinutes(1));
            assertTrue(again.isEmpty());
            assertEquals(first, store.revision(key("42")));
            assertEquals("v1", store.read(key("42")).lookup().optional().orElseThrow());
        }

        @Test
        @DisplayName("TTL 到期后既无数据也无版本")
        void expireAfterTtl() throws InterruptedException {
            store.write(key("42"), "u42", Duration.ofMillis(50));
            Thread.sleep(150);
            assertTrue(store.read(key("42")).lookup().isMiss());
            assertEquals(0L, store.revision(key("42")));
        }

        @Test
        @DisplayName("clear 只清本命名空间")
        void clearOnlyOwnNamespace() {
            SpringRedisValueStore<String> other = new SpringRedisValueStore<>(
                    factory, RedisKeyFormatter.defaults(), new StringRedisSerializer());
            CacheKey otherKey = new CacheKey("cache4j:test:other", "s1:a");
            store.write(key("a"), "va", Duration.ofMinutes(1));
            other.write(otherKey, "vb", Duration.ofMinutes(1));

            store.clear(NS);

            assertTrue(store.read(key("a")).lookup().isMiss());
            assertEquals("vb", other.read(otherKey).lookup().optional().orElseThrow());
            other.clear("cache4j:test:other");
        }

        @Test
        @DisplayName("热 key：上报达到阈值判 HOT，纯查询未见过判 UNKNOWN")
        void hotKeyReporting() {
            HotKeyReport hot = store.reportHotKey(NS, key("42"), 120L);
            assertEquals(HotKeyStatus.HOT, hot.status());
            // 只回传状态，不回传计数

            HotKeyReport unknown = store.reportHotKey(NS, key("never"), 0L);
            assertEquals(HotKeyStatus.UNKNOWN, unknown.status());
        }

        @Test
        @DisplayName("ping 可用")
        void ping() {
            assertTrue(store.ping());
        }

        @Test
        @DisplayName("值就是 serializer 输出的原始字节，一个字节都不加工")
        void valueIsRawSerializerOutput() {
            String value = "raw-value";
            store.write(key("raw"), value, Duration.ofMinutes(1));

            try (var connection = factory.getConnection()) {
                byte[] stored = connection.stringCommands()
                        .get(RedisKeyFormatter.defaults().data(key("raw")));
                assertArrayEquals("raw-value".getBytes(StandardCharsets.UTF_8), stored,
                        "Redis 里存的就是 StringRedisSerializer 的输出，没有 envelope 头");
            }
        }

        @Test
        @DisplayName("负副本用空字节数组表达：GET 返回空串而不是 nil")
        void negativeIsEmptyByteArray() {
            store.writeNegative(key("null"), Duration.ofMinutes(1));

            try (var connection = factory.getConnection()) {
                byte[] stored = connection.stringCommands()
                        .get(RedisKeyFormatter.defaults().data(key("null")));
                assertNotNull(stored, "负副本必须真的写入，才能与「没有 key」区分");
                assertEquals(0, stored.length);
            }
            assertTrue(store.read(key("null")).lookup().isNegative());
        }
    }
}
