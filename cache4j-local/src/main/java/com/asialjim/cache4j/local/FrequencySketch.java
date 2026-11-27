package com.asialjim.cache4j.local;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Count-Min Sketch：<strong>固定的、共享的</strong>频次粗筛。
 *
 * <p>它是热 key 判定的第一段：为所有 key 提供近似频次，但只占固定内存
 * （{@code (1 << widthBits) × depth} 个计数器，与访问过的 key 数量无关）。</p>
 *
 * <h2>两个刻意的方向性</h2>
 * <ul>
 *   <li><strong>只会高估，不会低估</strong>——多个 key 撞到同一格时计数被叠加。
 *       在"候选预选"阶段这是<strong>安全</strong>方向：漏判热点会让流量全打到中央，
 *       多判一个冷 key 只是让它多建一个窗口。</li>
 *   <li><strong>因此不能拿它直接升热</strong>——升热的判据必须来自精确窗口
 *       （见 {@link HotWindow}）。</li>
 * </ul>
 *
 * <h2>衰减</h2>
 * <p>每过一个 {@code windowMillis} 整体清零一次（惰性触发：由 {@link #incrementAndEstimate} 检查），
 * 所以统计的是"最近一个窗口"的访问量，而不是"从进程启动至今"。没有后台线程。</p>
 *
 * <p>计数器用 {@code long} 而非压缩位宽：撞格叠加容易把 4bit 计数打满，
 * 打满后就再也不会高估（退化成"永远处于阈值"），对候选阶段反而有害。用 {@code long}
 * 换来正确的饱和语义，代价是 {@code 8 字节 × 格数}。</p>
 *
 * @author Asial Jim
 */
final class FrequencySketch {

    private final int width;
    private final int depth;
    private final long[] table;
    private final AtomicLongArray seeds;
    private final long windowMillis;
    private final AtomicLong nextResetMillis = new AtomicLong();

    /**
     * @param widthBits    宽度位数（{@code width = 1 << widthBits}）
     * @param depth        深度（哈希函数个数）
     * @param windowMillis 统计窗口（毫秒），每过一个窗口整体衰减
     * @param nowMillis    当前毫秒时间
     */
    FrequencySketch(int widthBits, int depth, long windowMillis, long nowMillis) {
        int w = 1 << Math.max(1, widthBits);
        int d = Math.max(1, depth);
        this.width = w;
        this.depth = d;
        this.windowMillis = Math.max(1L, windowMillis);
        this.table = new long[w * d];
        this.seeds = new AtomicLongArray(d);
        for (int i = 0; i < d; i++) {
            // 每层一个独立的种子，避免不同层之间出现相同的碰撞模式
            seeds.set(i, mix(i * 0x9E3779B97F4A7C15L + 0x165667B19E3779F9L));
        }
        this.nextResetMillis.set(nowMillis + this.windowMillis);
    }

    /**
     * 记一次访问并返回当前估计值。
     *
     * @param key       key
     * @param nowMillis 当前毫秒时间
     * @return 估计频次（≥ 1，且只会高估）
     */
    long incrementAndEstimate(String key, long nowMillis) {
        rotateIfNeeded(nowMillis);
        long hash = hash(key);
        long estimate = Long.MAX_VALUE;
        for (int layer = 0; layer < depth; layer++) {
            int index = layer * width + slot(hash, seeds.get(layer));
            long value = ++table[index];
            if (value < estimate) {
                // Count-Min：取各层最小值，这仍然只会高估（不会低估）
                estimate = value;
            }
        }
        return estimate == Long.MAX_VALUE ? 0L : estimate;
    }

    /**
     * @return 计数器格数（观测用）
     */
    int size() {
        return width * depth;
    }

    /**
     * 整体清零（{@code clear()} 时调用）。
     */
    void reset() {
        for (int i = 0; i < table.length; i++) {
            table[i] = 0L;
        }
    }

    /**
     * 窗口到了就整体衰减；用 CAS 保证只有一个线程执行清零。
     */
    private void rotateIfNeeded(long nowMillis) {
        long next = nextResetMillis.get();
        if (nowMillis < next) {
            return;
        }
        long target = nowMillis + windowMillis;
        if (nextResetMillis.compareAndSet(next, target)) {
            reset();
        }
    }

    private int slot(long hash, long seed) {
        long mixed = mix(hash ^ seed);
        return (int) (mixed & (width - 1));      // width 是 2 的幂，用位与代替取模
    }

    private static long hash(String key) {
        // 用 String 自带的 hashCode 打底再混淆一次：CMS 只需要均匀，不需要密码学强度
        return mix(key == null ? 0L : key.hashCode());
    }

    /**
     * MurmurHash3 的 finalizer：把低位熵扩散到高位，避免 {@code hashCode} 的低位规律性
     * 直接落到固定宽度的槽位上。
     */
    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }
}
