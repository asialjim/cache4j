package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.central.redis.RedisAccessException;
import com.asialjim.cache4j.central.redis.RedisValueStore;
import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheKey;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.Lookup;
import com.asialjim.cache4j.core.central.HotKeyReport;
import com.asialjim.cache4j.core.central.HotKeyStatus;
import com.asialjim.cache4j.core.central.VersionedLookup;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.log.CacheLogger;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * {@link RedisValueStore} 的 Spring Data Redis 实现：<strong>这里才是真正与 Redis 发生 I/O 的地方</strong>。
 *
 * <p>本类独占 key 布局、envelope、Lua 与序列化：</p>
 * <ul>
 *   <li>序列化：使用业务提供的 {@link RedisSerializer}（encode/decode 各发生一次，不做二次包装）；</li>
 *   <li>原子性：值写入与版本更新在同一个 Lua 内完成；</li>
 *   <li>连接：复用 {@link RedisConnectionFactory}（连接池 / 集群 / 哨兵由 Spring 管理），
 *       底层用 Jedis、Lettuce 还是其他客户端由用户选择；</li>
 *   <li>版本元数据：{@code __rev} HASH，field 为编码后的 key，value 为
 *       {@code "<revision>|<expireAtMillis>"}；删除时连同 field 一起删。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class SpringRedisValueStore<V> implements RedisValueStore<V> {

    private static final CacheLogger LOG = CacheLogger.get(SpringRedisValueStore.class);

    private static final byte[] EMPTY = new byte[0];

    private final RedisConnectionFactory factory;
    private final RedisKeyFormatter keys;
    private final RedisSerializer<V> serializer;
    private final LongSupplier milliClock;

    /**
     * @param factory    连接工厂（由 Spring 提供）
     * @param keys       key 布局
     * @param serializer 值序列化器
     */
    public SpringRedisValueStore(RedisConnectionFactory factory, RedisKeyFormatter keys,
                                 RedisSerializer<V> serializer) {
        this(factory, keys, serializer, System::currentTimeMillis);
    }

    /**
     * @param factory    连接工厂
     * @param keys       key 布局
     * @param serializer 值序列化器
     * @param milliClock 毫秒时钟（测试可注入）
     */
    public SpringRedisValueStore(RedisConnectionFactory factory, RedisKeyFormatter keys,
                                 RedisSerializer<V> serializer, LongSupplier milliClock) {
        this.factory = factory;
        this.keys = keys;
        this.serializer = serializer;
        this.milliClock = milliClock;
    }

    // ------------------------------------------------------------------ 读

    @Override
    public VersionedLookup<V> read(CacheKey key) {
        long now = milliClock.getAsLong();
        byte[] revisionKey = keys.revision(key.namespace());
        byte[] dataKey = keys.data(key);
        byte[] field = keys.revisionField(key);

        List<?> result = eval(RedisScripts.READ_WITH_REVISION, ReturnType.MULTI,
                List.of(revisionKey, dataKey), List.of(field));
        long revision = decodeRevision(result.get(0));
        byte[] data = (byte[]) result.get(1);
        if (data == null) {
            return new VersionedLookup<>(revision, Lookup.miss());
        }
        // 负副本约定用"空字节数组"表示：GET 返回空串（不是 nil），与"没有 key"天然可区分。
        // 真值就是 serializer 的输出，一个字节都不加工。
        if (data.length == 0) {
            return new VersionedLookup<>(revision, Lookup.negative());
        }
        return new VersionedLookup<>(revision, Lookup.hit(decode(data, key)));
    }

    @Override
    public Map<CacheKey, VersionedLookup<V>> readAll(Collection<CacheKey> keys) {
        Map<CacheKey, VersionedLookup<V>> result = new HashMap<>(keys.size() * 2);
        keys.forEach(key -> result.put(key, read(key)));
        return result;
    }

    @Override
    public long revision(CacheKey key) {
        // 复用批量逻辑：会按 expireAt 判定失效，并顺手清掉死 field
        Long revision = revisions(List.of(key)).get(key);
        return revision == null ? 0L : revision;
    }

    @Override
    public Map<CacheKey, Long> revisions(Collection<CacheKey> requested) {
        long now = milliClock.getAsLong();
        Map<CacheKey, Long> result = new HashMap<>(requested.size() * 2);
        // 按 namespace 分组：版本表是按 namespace 的一张 HASH
        Map<String, List<CacheKey>> grouped = new LinkedHashMap<>();
        requested.forEach(key -> grouped.computeIfAbsent(key.namespace(), ignored -> new ArrayList<>())
                .add(key));

        grouped.forEach((namespace, namespaceKeys) -> {
            byte[] revisionKey = keys.revision(namespace);
            byte[][] fields = new byte[namespaceKeys.size()][];
            for (int i = 0; i < namespaceKeys.size(); i++) {
                fields[i] = keys.revisionField(namespaceKeys.get(i));
            }
            List<byte[]> raw = withConnection(connection ->
                    connection.hashCommands().hMGet(revisionKey, fields));

            List<byte[]> dead = new ArrayList<>();
            for (int i = 0; i < namespaceKeys.size(); i++) {
                byte[] value = raw == null || i >= raw.size() ? null : raw.get(i);
                if (value == null) {
                    continue;
                }
                RevAndExpire parsed = parseRevision(value);
                if (parsed.expireAtMillis() > 0L && parsed.expireAtMillis() <= now) {
                    // 数据已按 TTL 过期、版本 field 还留着：清掉并视作失效（对编排层透明）
                    dead.add(fields[i]);
                    continue;
                }
                result.put(namespaceKeys.get(i), parsed.revision());
            }
            if (!dead.isEmpty()) {
                byte[][] deadFields = dead.toArray(new byte[0][]);
                withConnection(connection -> connection.hashCommands().hDel(revisionKey, deadFields));
            }
        });
        return result;
    }

    // ------------------------------------------------------------------ 写

    @Override
    public long write(CacheKey key, V value, Duration ttl) {
        return doWrite(RedisScripts.WRITE_WITH_REVISION, key, encode(value), ttl);
    }

    @Override
    public long writeNegative(CacheKey key, Duration ttl) {
        // 负副本 = 空字节数组（见 read 的约定）
        return doWrite(RedisScripts.WRITE_WITH_REVISION, key, new byte[0], ttl);
    }

    @Override
    public OptionalLong writeIfAbsent(CacheKey key, V value, Duration ttl) {
        try {
            Object result = eval(RedisScripts.WRITE_IF_ABSENT, ReturnType.INTEGER,
                    List.of(keys.revision(key.namespace()), keys.data(key)),
                    writeArgs(key, encode(value), ttl));
            return result == null ? OptionalLong.empty() : OptionalLong.of(((Number) result).longValue());
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    @Override
    public void delete(CacheKey key) {
        eval(RedisScripts.DELETE, ReturnType.INTEGER,
                List.of(keys.revision(key.namespace()), keys.data(key)),
                List.of(keys.revisionField(key)));
    }

    @Override
    public void clear(String namespace) {
        byte[] revisionKey = keys.revision(namespace);
        byte[] prefix = keys.namespacePrefix(namespace);
        byte[] match = concat(prefix, "*".getBytes(StandardCharsets.UTF_8));
        try (RedisConnection connection = factory.getConnection()) {
            // 先删版本表：此后任何访问都会因"版本不存在"而回中央
            connection.keyCommands().del(revisionKey);
            ScanOptions options = ScanOptions.scanOptions().match(new String(match, StandardCharsets.UTF_8))
                    .count(500).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                List<byte[]> batch = new ArrayList<>(500);
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 500) {
                        connection.keyCommands().unlink(batch.toArray(new byte[0][]));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    connection.keyCommands().unlink(batch.toArray(new byte[0][]));
                }
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    // ------------------------------------------------------------------ 热 key

    @Override
    public HotKeyReport reportHotKey(String namespace, CacheKey key, long delta) {
        Map<CacheKey, HotKeyReport> reports = reportHotKeyAll(namespace, Map.of(key, delta));
        return reports.getOrDefault(key, HotKeyReport.unknown());
    }

    @Override
    public Map<CacheKey, HotKeyReport> reportHotKeyAll(String namespace, Map<CacheKey, Long> deltas) {
        LocalCacheSpec.GlobalSpec global = globalSpecs == null ? null : globalSpecs.apply(namespace);
        if (global == null) {
            // 未配置 dynamic.global：不猜阈值
            Map<CacheKey, HotKeyReport> unknown = new HashMap<>(deltas.size() * 2);
            deltas.keySet().forEach(key -> unknown.put(key, HotKeyReport.unknown()));
            return unknown;
        }
        long windowMillis = global.window().toMillis();
        long now = milliClock.getAsLong();
        long windowId = now / windowMillis;
        byte[] meta = keys.hotMeta(namespace);
        byte[] current = keys.hotWindow(namespace, windowId);
        byte[] previous = keys.hotWindow(namespace, windowId - 1L);
        long windowTtlMillis = windowMillis * 2L;
        long metaTtlMillis = windowMillis * Math.max(1L, global.staleWindows() + 1L) * 2L;

        Map<CacheKey, HotKeyReport> result = new HashMap<>(deltas.size() * 2);
        deltas.forEach((key, delta) -> {
            List<?> raw = eval(RedisScripts.HOT_REPORT_AND_STATUS, ReturnType.MULTI,
                    List.of(meta, current, previous),
                    List.of(keys.revisionField(key),
                            ascii(delta),
                            ascii(windowId),
                            ascii(global.hotCount()),
                            ascii(global.coolCount()),
                            ascii(global.demoteWindows()),
                            ascii(global.staleWindows()),
                            ascii(windowTtlMillis),
                            ascii(metaTtlMillis)));
            result.put(key, toReport(raw));
        });
        return result;
    }

    @Override
    public boolean ping() {
        try (RedisConnection connection = factory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.commands().ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        // 连接工厂由 Spring 拥有，这里不关闭它
    }

    /**
     * 全局阈值解析器（由适配层注入；未配置的 namespace 返回 {@code null}）。
     */
    private java.util.function.Function<String, LocalCacheSpec.GlobalSpec> globalSpecs;

    /**
     * 设置中央阈值解析器（供热 key 上报使用）。
     *
     * @param globalSpecs namespace → 中央阈值
     * @return this
     */
    public SpringRedisValueStore<V> globalSpecs(
            java.util.function.Function<String, LocalCacheSpec.GlobalSpec> globalSpecs) {
        this.globalSpecs = globalSpecs;
        return this;
    }

    // ------------------------------------------------------------------ 内部

    private long doWrite(byte[] script, CacheKey key, byte[] raw, Duration ttl) {
        Object result = eval(script, ReturnType.INTEGER,
                List.of(keys.revision(key.namespace()), keys.data(key)),
                writeArgs(key, raw, ttl));
        return result == null ? 0L : ((Number) result).longValue();
    }

    private List<byte[]> writeArgs(CacheKey key, byte[] raw, Duration ttl) {
        long now = milliClock.getAsLong();
        long expireAt = ttl == null || ttl.isZero() ? 0L : now + ttl.toMillis();
        List<byte[]> args = new ArrayList<>(5);
        args.add(keys.revisionField(key));
        args.add(raw);
        args.add(ttl == null || ttl.isZero() ? EMPTY : ascii(ttl.toMillis()));
        args.add(ascii(now));
        args.add(ascii(expireAt));
        return args;
    }

    @SuppressWarnings("unchecked")
    private <T> T eval(byte[] script, ReturnType returnType, List<byte[]> scriptKeys, List<byte[]> args) {
        byte[][] keysAndArgs = new byte[scriptKeys.size() + args.size()][];
        for (int i = 0; i < scriptKeys.size(); i++) {
            keysAndArgs[i] = scriptKeys.get(i);
        }
        for (int i = 0; i < args.size(); i++) {
            keysAndArgs[scriptKeys.size() + i] = args.get(i);
        }
        try (RedisConnection connection = factory.getConnection()) {
            return (T) connection.scriptingCommands().eval(script, returnType,
                    scriptKeys.size(), keysAndArgs);
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    private <T> T withConnection(java.util.function.Function<RedisConnection, T> action) {
        try (RedisConnection connection = factory.getConnection()) {
            return action.apply(connection);
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    private V decode(byte[] payload, CacheKey key) {
        try {
            return serializer.deserialize(payload);
        } catch (RuntimeException e) {
            // 数据损坏必须可见：ERROR 级别，且永远不降级
            LOG.error(CacheLogger.Subject.REDIS_STORE, "编解码", "反序列化失败，key=" + key.masked()
                    + "，serializer=" + serializer.getClass().getSimpleName(), e);
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED, "反序列化失败", e);
        }
    }

    private byte[] encode(V value) {
        try {
            byte[] payload = serializer.serialize(value);
            if (payload == null) {
                LOG.error(CacheLogger.Subject.REDIS_STORE, "编解码", "serializer 返回 null，value 类型="
                        + (value == null ? "null" : value.getClass().getName()));
                throw new CacheException(CacheResCode.CODEC_ENCODE_FAILED, "serializer 返回 null");
            }
            return payload;
        } catch (CacheException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error(CacheLogger.Subject.REDIS_STORE, "编解码", "序列化失败，value 类型="
                    + (value == null ? "null" : value.getClass().getName())
                    + "，serializer=" + serializer.getClass().getSimpleName(), e);
            throw new CacheException(CacheResCode.CODEC_ENCODE_FAILED, "序列化失败", e);
        }
    }

    /**
     * 解析 {@code "<revision>|<expireAtMillis>"}。
     *
     * @param raw 字节
     * @return 版本 + 过期时刻
     */
    static RevAndExpire parseRevision(byte[] raw) {
        if (raw == null) {
            return new RevAndExpire(0L, 0L);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        int split = text.indexOf('|');
        if (split < 0) {
            return new RevAndExpire(parseLong(text), 0L);
        }
        return new RevAndExpire(parseLong(text.substring(0, split)),
                parseLong(text.substring(split + 1)));
    }

    private static long decodeRevision(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof byte[] bytes) {
            return parseRevision(bytes).revision();
        }
        return ((Number) raw).longValue();
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static HotKeyReport toReport(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return HotKeyReport.unknown();
        }
        // Lua 仍返回 4 元组（status / 合计 / lastActive / lastHot），但合计字段**不再回传**：
        // 中央计数是"所有节点增量之和"，回灌本地会导致下次上报重复放大（见 HotKeyReport 契约）。
        HotKeyStatus status = parseStatus(raw.get(0));
        long active = parseLong(text(raw.get(2)));
        long hot = parseLong(text(raw.get(3)));
        return new HotKeyReport(status, active, hot);
    }

    private static HotKeyStatus parseStatus(Object raw) {
        String text = text(raw);
        try {
            return HotKeyStatus.valueOf(text);
        } catch (IllegalArgumentException e) {
            return HotKeyStatus.UNKNOWN;
        }
    }

    private static String text(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw.toString();
    }

    /**
     * 把 Spring 的数据访问异常翻译成适配层统一异常。
     *
     * @param e Spring 异常
     * @return 统一异常
     */
    static RedisAccessException translate(DataAccessException e) {
        if (e instanceof RedisConnectionFailureException) {
            return new RedisAccessException(RedisAccessException.Kind.UNAVAILABLE, e.getMessage(), e);
        }
        if (e instanceof QueryTimeoutException) {
            return new RedisAccessException(RedisAccessException.Kind.TIMEOUT, e.getMessage(), e);
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("timeout")) {
            return new RedisAccessException(RedisAccessException.Kind.TIMEOUT, e.getMessage(), e);
        }
        return new RedisAccessException(RedisAccessException.Kind.COMMAND_FAILED, e.getMessage(), e);
    }

    /**
     * 版本 + 数据过期时刻。
     *
     * @param revision       版本
     * @param expireAtMillis 过期时刻；{@code 0} 表示永不过期
     */
    record RevAndExpire(long revision, long expireAtMillis) {
    }
}
