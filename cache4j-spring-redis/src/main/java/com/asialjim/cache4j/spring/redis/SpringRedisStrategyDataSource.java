package com.asialjim.cache4j.spring.redis;

import com.asialjim.cache4j.core.strategy.CacheStrategyDataSource;
import com.asialjim.cache4j.core.strategy.Page;
import com.asialjim.cache4j.core.strategy.StoredPolicy;
import com.asialjim.cache4j.core.strategy.TextPolicyCodec;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 策略数据源的 Redis 实现：一条记录一个 HASH field，value 是 {@link TextPolicyCodec} 的可读文本。
 *
 * <pre>
 * KEYS[1] = cache4j:strategy          HASH，field = namespace，value = 策略文本
 * </pre>
 *
 * <p>用 {@code redis-cli hgetall cache4j:strategy} 就能直接看懂每一条策略，不需要额外工具。</p>
 *
 * <h2>契约落实</h2>
 * <ul>
 *   <li>{@link #load}：{@code HGET}，未命中返回 empty；</li>
 *   <li>{@link #query}：{@code HSCAN ... MATCH} 流式读取，只保留当前页的 {@code size} 条，
 *       不把全量记录拉回内存；为了给出准确的 {@code total} 需要遍历一遍游标（分页语义的必要代价）。
 *       <strong>{@code MATCH} 只能匹配 field（namespace）</strong>，description 的模糊匹配本实现不支持——
 *       Redis HASH 没法按 value 过滤；</li>
 *   <li>{@link #register}：{@code HSETNX}，已存在返回 {@code false}，绝不覆盖；</li>
 *   <li>{@link #update}：Lua 里比较文本中的 {@code configVersion} 再写，原子 CAS；
 *       新版本号 = 期望版本 + 1，由本类生成（契约要求"数据源生成且单调递增"）。</li>
 * </ul>
 *
 * <p><strong>策略数据源是业务存储</strong>：本类只是"用 Redis 存策略"的现成实现，
 * 不用 Redis 的业务换一个 {@link CacheStrategyDataSource} 实现即可，门面与装配层不需要改。</p>
 *
 * @author Asial Jim
 */
public class SpringRedisStrategyDataSource implements CacheStrategyDataSource {

    /**
     * 默认 HASH key。
     */
    public static final String DEFAULT_KEY = "cache4j:strategy";

    /**
     * CAS 更新：比较文本里的 configVersion，一致才写。
     *
     * <pre>
     * KEYS[1] = HASH key
     * ARGV[1] = field(namespace)
     * ARGV[2] = 期望的 configVersion
     * ARGV[3] = 新文本
     * 返回 1=成功，0=版本冲突，-1=记录不存在
     * </pre>
     */
    private static final RedisScript<Long> CAS_UPDATE = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local cur = redis.call('HGET', KEYS[1], ARGV[1]) "
                    + "if not cur then return -1 end "
                    + "local ver = string.match(cur, 'configVersion=(%d+)') "
                    + "if (not ver) or tonumber(ver) ~= tonumber(ARGV[2]) then return 0 end "
                    + "redis.call('HSET', KEYS[1], ARGV[1], ARGV[3]) "
                    + "return 1", Long.class);

    private final RedisConnectionFactory connectionFactory;

    private final byte[] key;

    private final TextPolicyCodec codec = new TextPolicyCodec();

    /**
     * @param connectionFactory Redis 连接工厂
     */
    public SpringRedisStrategyDataSource(RedisConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_KEY);
    }

    /**
     * @param connectionFactory Redis 连接工厂
     * @param hashKey           策略 HASH 的 key（多环境共存时可加前缀）
     */
    public SpringRedisStrategyDataSource(RedisConnectionFactory connectionFactory, String hashKey) {
        this.connectionFactory = connectionFactory;
        this.key = bytes(hashKey);
    }

    @Override
    public Optional<StoredPolicy> load(String namespace) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            byte[] raw = connection.hashCommands().hGet(key, bytes(namespace));
            return raw == null || raw.length == 0 ? Optional.empty() : Optional.of(codec.decode(raw));
        }
    }

    @Override
    public Page<StoredPolicy> query(String keyword, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        long offset = (long) safePage * safeSize;

        List<StoredPolicy> items = new ArrayList<>(safeSize);
        long matched = 0;
        ScanOptions options = (keyword == null || keyword.isBlank())
                ? ScanOptions.scanOptions().count(256).build()
                : ScanOptions.scanOptions().match("*" + keyword.trim() + "*").count(256).build();
        try (RedisConnection connection = connectionFactory.getConnection();
             Cursor<Map.Entry<byte[], byte[]>> cursor = connection.hashCommands().hScan(key, options)) {
            while (cursor.hasNext()) {
                Map.Entry<byte[], byte[]> entry = cursor.next();
                if (matched >= offset && items.size() < safeSize) {
                    items.add(codec.decode(entry.getValue()));
                }
                matched++;
            }
        }
        return new Page<>(items, safePage, safeSize, matched);
    }

    @Override
    public boolean register(StoredPolicy policy) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Boolean added = connection.hashCommands()
                    .hSetNX(key, bytes(policy.namespace()), codec.encode(policy));
            return Boolean.TRUE.equals(added);
        }
    }

    @Override
    public OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
        long newVersion = expectedConfigVersion + 1;
        StoredPolicy stored = new StoredPolicy(
                policy.namespace(), policy.description(), policy.strategy(),
                policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                policy.version(), policy.dynamic(),
                policy.centralTtl(), policy.centralNullTtl(), policy.failOpen(),
                newVersion, policy.updatedAtMillis(), policy.updatedBy(), policy.reason());
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Long result = connection.scriptingCommands().eval(CAS_UPDATE.getScriptAsString().getBytes(StandardCharsets.UTF_8),
                    ReturnType.INTEGER, 1, key, bytes(policy.namespace()),
                    bytes(Long.toString(expectedConfigVersion)), codec.encode(stored));
            return result != null && result == 1L ? OptionalLong.of(newVersion) : OptionalLong.empty();
        }
    }

    /**
     * @return 策略 HASH 的 key（便于运维脚本复用）
     */
    public String hashKey() {
        return new String(key, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
