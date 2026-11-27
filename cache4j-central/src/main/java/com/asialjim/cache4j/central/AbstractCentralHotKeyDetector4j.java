package com.asialjim.cache4j.central;

import com.asialjim.cache4j.Cache4jHotKeyDetector;
import com.asialjim.cache4j.HotKeyStatus;
import com.asialjim.cache4j.strategy.CacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 中央热 key 探测的骨架：本类只负责<strong>分片</strong>与<strong>阈值判定</strong>，
 * 时间窗口完全交给存储层。
 *
 * <h2>窗口不归本层管</h2>
 * <p>热度是"某个时间窗口内的访问次数"，而窗口必须由<strong>中央时钟</strong>划分——
 * 本地时钟不可信（装完系统没同步 NTP、容器长期运行漂移、云主机迁移跳变；时区倒不是问题，
 * {@code currentTimeMillis} 是 UTC epoch、跨时区取值相同）。而唯一能拿到中央时钟的地方
 * 就是存储端。</p>
 *
 * <p>所以本层<strong>不接收时间、也不计算窗口</strong>。存储层在实现
 * {@link #accumulate} / {@link #readCounts} 时自己这么干：</p>
 *
 * <pre>{@code
 * -- 一次 Lua：取中央时间、划窗、累加、设过期（key 形状由实现自己定）
 * local t = redis.call('TIME')
 * local windowStart = math.floor(tonumber(t[1]) / windowSeconds) * windowSeconds
 * local k = prefix .. windowStart     -- prefix = hotKeyPrefix(ns)，以冒号结尾
 * redis.call('HINCRBY', k, field, delta)
 * redis.call('EXPIRE', k, windowSeconds * 2)
 * }</pre>
 *
 * <p>key 的<strong>前缀</strong>由抽象层统一定义（{@link CentralKeyLayout4j#hotKeyPrefix}，
 * 形如 {@code C4J-L2:{ns}:#{hash}:__hotkey:}）——namespace 下的 key 空间是共享命名约定，
 * 不能让各实现各写一套。而<strong>窗口段</strong>由存储层自己算完再拼上去，
 * 因为窗口只能用中央时钟划分。</p>
 *
 * <h2>窗口由中央缓存自己算</h2>
 * <p>没有"本地估算一份中央时间"这种中间路线：累加与读取<strong>本来就是远程调用</strong>，
 * 把 {@code TIME} 放进同一段 Lua 就是零额外往返、绝对准确、永不漂移。
 * 任何"启动同步一次、之后靠本地推算"的做法都会漂移（晶振频率差、虚拟机挂起），
 * 还必须额外维护重同步状态机——用复杂度换一个本来就不存在的收益。</p>
 * <p>因此本层不提供时钟工具，实现方也不该拿 {@code System.currentTimeMillis()}
 * 或任何本地推算值来划窗。</p>
 *
 * <h2>存储层必须自己合并相邻两个窗口</h2>
 * <p>窗口切换那一刻新表为空，只看当前窗口会得到"全都不热"。实现方读计数时必须
 * 合并 {@code cur} 与 {@code cur - hotWindow}，{@link #readCounts} 返回的应当是
 * <strong>合并后的滑动计数</strong>。</p>
 *
 * <h2>本层负责的两件事</h2>
 * <ol>
 *   <li><strong>分片</strong>：按 {@code hotReportBatchMax} 切块、按
 *       {@code hotReportPartitions} 拆请求——控制单次请求体积，但都在同一轮内完成；</li>
 *   <li><strong>阈值判定</strong>：用 {@code globalHotQps} / {@code globalCoolQps} /
 *       {@code globalClientEstimate} 把全局阈值摊到单节点，再判
 *       {@code HOT} / {@code COOLING} / 滞回区不表态。</li>
 * </ol>
 *
 * <h2>失败语义：吞掉，返回空 Map</h2>
 * <p>返回空 Map 的含义是"中央没有结论"，本地按 {@link HotKeyStatus#UNKNOWN} 处理——
 * <strong>不降热</strong>，正是抖动时该有的保守行为。</p>
 *
 * <h2>本层不负责</h2>
 * <ul>
 *   <li>{@code globalDemoteWindows}（连续几次 COOLING 才真降热）——在本地侧，
 *       因为"连续几次"是本地维护的上报历史；</li>
 *   <li>{@code globalStaleWindows}（几个周期没表态算陈旧）——同在本地侧。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public abstract class AbstractCentralHotKeyDetector4j implements Cache4jHotKeyDetector {

    private static final Logger log = LoggerFactory.getLogger(AbstractCentralHotKeyDetector4j.class);

    @Override
    public final HotKeyStatus status(String key, CacheStrategy strategy) {
        return verdicts(Set.of(key), strategy).getOrDefault(key, HotKeyStatus.UNKNOWN);
    }

    @Override
    public final Map<String, HotKeyStatus> report(Map<String, Long> batch, CacheStrategy strategy) {
        if (batch == null || batch.isEmpty()) {
            return Map.of();
        }
        try {
            int batchMax = Math.max(1, intOf(strategy.hotReportBatchMax(), 256));
            int partitions = Math.max(1, intOf(strategy.hotReportPartitions(), 1));
            // 拆块：单块不超过 hotReportBatchMax，同一轮内全部累加完
            for (Map<String, Long> chunk : split(batch, batchMax)) {
                for (Map<String, Long> shard : shard(chunk, partitions)) {
                    accumulate(shard, strategy);
                }
            }
            return verdicts(batch.keySet(), strategy);
        } catch (RuntimeException e) {
            log.warn("[central-hotkey]-report-failed size={} reason={}", batch.size(), e.toString());
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ 原语（子类实现）

    /**
     * 把一批访问增量累加到中央的热度计数里。
     *
     * <p>key 前缀取自 {@link CentralKeyLayout4j#hotKeyPrefix}，<strong>窗口段由实现方自己算</strong>：
     * 用存储端的时钟（Redis {@code TIME}）取当前时间，按 {@code hotWindow} 划窗后拼上去。
     * 整段放进同一段 Lua 即可一次往返完成。</p>
     *
     * <p><strong>禁止使用 {@code System.currentTimeMillis()}</strong>：
     * 一台没同步时钟的机器就足以让它的计数落进一个其它节点读不到的窗口，
     * 那部分流量会在全局热度里凭空消失。</p>
     *
     * @param batch    key → 本次上报的访问增量
     * @param strategy 策略
     */
    protected abstract void accumulate(Map<String, Long> batch, CacheStrategy strategy);

    /**
     * 读一批 key 的<strong>滑动计数</strong>。
     *
     * <p>实现方必须合并相邻两个窗口（当前 + 上一窗口）后返回——窗口切换时新表为空，
     * 只读当前窗口会被误判成"全都不热"，进而误触发 {@code COOLING}。</p>
     *
     * <p>窗口同样由实现方自己用中央时钟算，本层不传时间也不传窗口。</p>
     *
     * @param keys     key 集合
     * @param strategy 策略
     * @return key → 合并后的计数；没有记录的 key 不要放进结果
     */
    protected abstract Map<String, Long> readCounts(Set<String> keys, CacheStrategy strategy);

    // ------------------------------------------------------------------ 模板：阈值判定

    private Map<String, HotKeyStatus> verdicts(Set<String> keys, CacheStrategy strategy) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        int batchMax = Math.max(1, intOf(strategy.hotReportBatchMax(), 256));
        Map<String, Long> totals = new HashMap<>(Math.min(keys.size() * 2, 1024));
        try {
            for (List<String> chunk : chunk(keys, batchMax)) {
                Map<String, Long> part = readCounts(Set.copyOf(chunk), strategy);
                if (part != null) {
                    totals.putAll(part);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[central-hotkey]-read-failed size={} reason={}", keys.size(), e.toString());
            return Map.of();
        }

        long hotCount = hotCount(strategy);
        long coolCount = coolCount(strategy);
        Map<String, HotKeyStatus> result = new HashMap<>(Math.min(totals.size() * 2, 1024));
        totals.forEach((key, count) -> {
            if (count == null) {
                return;
            }
            if (count >= hotCount) {
                result.put(key, HotKeyStatus.HOT);
            } else if (count <= coolCount) {
                result.put(key, HotKeyStatus.COOLING);
            }
            // 介于两者之间（滞回区）不表态：既不催热也不催降
        });
        return result;
    }

    /**
     * 全局升热阈值摊到单节点后的窗口内次数。
     */
    private long hotCount(CacheStrategy strategy) {
        long global = longOf(strategy.globalHotQps(), longOf(strategy.hotThresholdQps(), 50L));
        return share(global, strategy) * windowSeconds(strategy);
    }

    /**
     * 全局降温阈值摊到单节点后的窗口内次数。
     */
    private long coolCount(CacheStrategy strategy) {
        long global = longOf(strategy.globalCoolQps(), longOf(strategy.coolQps(), 15L));
        return share(global, strategy) * windowSeconds(strategy);
    }

    private static long share(long globalQps, CacheStrategy strategy) {
        long clients = Math.max(1L, intOf(strategy.globalClientEstimate(), 1));
        return Math.max(1L, (globalQps + clients - 1L) / clients);
    }

    private static long windowSeconds(CacheStrategy strategy) {
        return Math.max(1L, longOf(strategy.hotWindow(), 30L));
    }

    // ------------------------------------------------------------------ 工具

    private static List<Map<String, Long>> split(Map<String, Long> batch, int batchMax) {
        List<Map<String, Long>> chunks = new ArrayList<>();
        Map<String, Long> current = new LinkedHashMap<>(Math.min(batch.size(), batchMax));
        for (Map.Entry<String, Long> entry : batch.entrySet()) {
            if (current.size() >= batchMax) {
                chunks.add(current);
                current = new LinkedHashMap<>(batchMax);
            }
            current.put(entry.getKey(), entry.getValue());
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /**
     * 按 key 哈希分片：同一个 key 永远落在同一片，实现方可以据此做幂等或缓存。
     */
    private static List<Map<String, Long>> shard(Map<String, Long> chunk, int partitions) {
        if (partitions <= 1 || chunk.size() <= 1) {
            return List.of(chunk);
        }
        Map<Integer, Map<String, Long>> groups = new LinkedHashMap<>();
        chunk.forEach((key, count) -> groups
                .computeIfAbsent(Math.floorMod(key.hashCode(), partitions), i -> new LinkedHashMap<>())
                .put(key, count));
        return new ArrayList<>(groups.values());
    }

    private static List<List<String>> chunk(Set<String> keys, int batchMax) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>(Math.min(keys.size(), batchMax));
        for (String key : keys) {
            if (current.size() >= batchMax) {
                chunks.add(current);
                current = new ArrayList<>(batchMax);
            }
            current.add(key);
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static long longOf(Supplier<Long> supplier, long fallback) {
        return Optional.ofNullable(supplier).map(Supplier::get).orElse(fallback);
    }

    private static int intOf(Supplier<Integer> supplier, int fallback) {
        return Optional.ofNullable(supplier).map(Supplier::get).orElse(fallback);
    }
}
