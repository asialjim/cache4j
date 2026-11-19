package com.asialjim.cache4j.spring.redis;

import java.nio.charset.StandardCharsets;

/**
 * Lua 脚本（<strong>适配层职责</strong>）。
 *
 * <p>"值与版本的原子性"由这些脚本保证；它们属于适配层，换客户端时按同一语义重写即可。</p>
 *
 * @author Asial Jim
 */
final class RedisScripts {

    /**
     * 读：一次往返取回版本与值。
     *
     * <pre>{@code
     * KEYS[1] = __rev, KEYS[2] = data
     * ARGV[1] = field(encodedKey)
     * 返回 { revisionBytesOrNil, dataBytesOrNil }
     * }</pre>
     */
    static final byte[] READ_WITH_REVISION = script("""
            local rev = redis.call('HGET', KEYS[1], ARGV[1])
            local data = redis.call('GET', KEYS[2])
            return { rev, data }
            """);

    /**
     * 写：写值 + 更新版本（同一脚本内原子）。
     *
     * <pre>{@code
     * KEYS[1] = __rev, KEYS[2] = data
     * ARGV[1] = field, ARGV[2] = value, ARGV[3] = ttlMillis ('' = 无过期),
     * ARGV[4] = now, ARGV[5] = expireAtMillis (0 = 永不过期)
     * 返回新版本
     * }</pre>
     */
    static final byte[] WRITE_WITH_REVISION = script("""
            -- field 的格式是 "<revision>|<expireAt>"，只取竖线前那段
            local raw = redis.call('HGET', KEYS[1], ARGV[1])
            local cur = 0
            if raw then
              cur = tonumber(string.match(raw, '^(%-?%d+)') or '0')
            end
            local now = tonumber(ARGV[4])
            local newRev = now
            if cur + 1 > newRev then
              newRev = cur + 1
            end
            if ARGV[3] == '' then
              redis.call('SET', KEYS[2], ARGV[2])
            else
              redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            end
            redis.call('HSET', KEYS[1], ARGV[1], newRev .. '|' .. ARGV[5])
            return newRev
            """);

    /**
     * 不存在才写：成功才更新版本。
     *
     * <pre>{@code
     * KEYS[1] = __rev, KEYS[2] = data
     * ARGV 同 WRITE_WITH_REVISION
     * 返回新版本（写入成功）或 nil（已存在）
     * }</pre>
     */
    static final byte[] WRITE_IF_ABSENT = script("""
            local exists = redis.call('EXISTS', KEYS[2])
            if exists == 1 then
              return nil
            end
            local raw = redis.call('HGET', KEYS[1], ARGV[1])
            if raw then
              return nil
            end
            local cur = 0
            local now = tonumber(ARGV[4])
            local newRev = now
            if cur + 1 > newRev then
              newRev = cur + 1
            end
            if ARGV[3] == '' then
              redis.call('SET', KEYS[2], ARGV[2])
            else
              redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            end
            redis.call('HSET', KEYS[1], ARGV[1], newRev .. '|' .. ARGV[5])
            return newRev
            """);

    /**
     * 删除：删数据 + 删版本 field（不保留版本）。
     *
     * <pre>{@code
     * KEYS[1] = __rev, KEYS[2] = data; ARGV[1] = field
     * }</pre>
     */
    static final byte[] DELETE = script("""
            redis.call('DEL', KEYS[2])
            redis.call('HDEL', KEYS[1], ARGV[1])
            return 1
            """);

    /**
     * 热 key 上报 + 状态计算（一次往返）。
     *
     * <pre>{@code
     * KEYS[1] = meta, KEYS[2] = 当前窗口, KEYS[3] = 上一窗口
     * ARGV[1] = field, ARGV[2] = delta, ARGV[3] = windowId,
     * ARGV[4] = hotThreshold, ARGV[5] = coolThreshold,
     * ARGV[6] = demoteWindows, ARGV[7] = staleWindows,
     * ARGV[8] = windowTtlMillis, ARGV[9] = metaTtlMillis
     * 返回 { status, total, active, hot }
     * }</pre>
     */
    static final byte[] HOT_REPORT_AND_STATUS = script("""
            if tonumber(ARGV[2]) > 0 then
              redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[1])
              redis.call('PEXPIRE', KEYS[2], ARGV[8])
            end

            local cur  = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
            local prev = tonumber(redis.call('ZSCORE', KEYS[3], ARGV[1]) or '0')
            local total = cur + prev

            local active = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':active') or '-1')
            local hot    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':hot')    or '-1')
            local low    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':lowSince') or '-1')

            if tonumber(ARGV[2]) == 0 and active < 0 and total == 0 then
              return { 'UNKNOWN', '0', '-1', '-1' }
            end

            local status = 'ACTIVE'

            if active >= 0 and tonumber(ARGV[3]) - active >= tonumber(ARGV[7]) then
              status = 'COOLING'
            elseif total >= tonumber(ARGV[4]) then
              status = 'HOT'
              hot = tonumber(ARGV[3])
              low = -1
            elseif total < tonumber(ARGV[5]) then
              if low < 0 then low = tonumber(ARGV[3]) end
              if tonumber(ARGV[3]) - low >= tonumber(ARGV[6]) then
                status = 'COOLING'
              end
            else
              low = -1
            end

            redis.call('HSET', KEYS[1],
              ARGV[1] .. ':active', ARGV[3],
              ARGV[1] .. ':hot', hot,
              ARGV[1] .. ':lowSince', low)
            redis.call('PEXPIRE', KEYS[1], ARGV[9])

            return { status, tostring(total), tostring(active), tostring(hot) }
            """);

    private RedisScripts() {
    }

    private static byte[] script(String source) {
        return source.stripIndent().getBytes(StandardCharsets.UTF_8);
    }
}
