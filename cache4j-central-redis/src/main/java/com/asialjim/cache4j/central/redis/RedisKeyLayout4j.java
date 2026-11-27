package com.asialjim.cache4j.central.redis;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.central.CentralKeyLayout4j;

/**
 * Redis 场景下的 key 取值。
 *
 * <p>布局本身（前缀、hash tag、field 规则）<strong>全部由 {@link CentralKeyLayout4j} 决定</strong>，
 * 本类只是一层薄包装，把这几个方法按 Redis 适配层的使用习惯命名。不重新拼任何东西——
 * 两套拼接逻辑必然会在某天对不上。</p>
 *
 * <p>产出的是 {@code String}：适配层通常用 {@code StringRedisSerializer} 作 key 序列化器，
 * key 保持字符串可以直接用，也便于在 {@code redis-cli} 里排查。</p>
 *
 * @author Asial Jim
 */
public final class RedisKeyLayout4j {

    /**
     * 无状态单例。
     */
    public static final RedisKeyLayout4j INSTANCE = new RedisKeyLayout4j();

    private static final CentralKeyLayout4j LAYOUT = CentralKeyLayout4j.INSTANCE;

    private RedisKeyLayout4j() {
    }

    /**
     * 数据 key。
     *
     * @param key 缓存键
     * @return {@code C4J-L2:{ns}:#{hash}:{key}}
     */
    public String dataKey(Cache4jKey key) {
        return LAYOUT.dataKey(key);
    }

    /**
     * 数据 key 前缀，以冒号结尾。
     *
     * <p>清空 namespace 时作为 {@code SCAN MATCH} 的模板：{@code prefix + "*"}
     * 会命中本 namespace 下的全部三类 key（数据 / {@code __rev} / {@code __hotkey:*}）。</p>
     *
     * @param namespace 命名空间
     * @return 前缀
     */
    public String dataKeyPrefix(String namespace) {
        return LAYOUT.dataKeyPrefix(namespace);
    }

    /**
     * 版本 HASH 的 key。
     *
     * @param namespace 命名空间
     * @return {@code C4J-L2:{ns}:#{hash}:__rev}
     */
    public String revisionKey(String namespace) {
        return LAYOUT.revisionKey(namespace);
    }

    /**
     * 版本 HASH 的 field。
     *
     * @param key 缓存键
     * @return 业务 key
     */
    public String revisionField(Cache4jKey key) {
        return LAYOUT.revisionField(key);
    }

    /**
     * 热度 HASH 的 key 前缀，以冒号结尾；窗口段由实现自己拼。
     *
     * @param namespace 命名空间
     * @return {@code C4J-L2:{ns}:#{hash}:__hotkey:}
     */
    public String hotKeyPrefix(String namespace) {
        return LAYOUT.hotKeyPrefix(namespace);
    }

    /**
     * 热度 HASH 的 field。
     *
     * @param key 缓存键
     * @return 业务 key
     */
    public String hotKeyField(Cache4jKey key) {
        return LAYOUT.hotKeyField(key);
    }

    /**
     * 热度 HASH 的 field（业务 key）。
     *
     * @param key 业务 key
     * @return field
     */
    public String hotKeyField(String key) {
        return LAYOUT.hotKeyField(key);
    }
}
