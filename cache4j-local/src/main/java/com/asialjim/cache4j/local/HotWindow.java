package com.asialjim.cache4j.local;

/**
 * 每个 key 的<strong>精确</strong>滑动窗口计数（16 片环形）。
 *
 * <p>它是热 key 判定的第二段。{@link FrequencySketch} 只会高估，用它升热会把冷 key 误判成热；
 * 所以只对"CMS 粗筛达到候选阈值"的 key 建这个窗口，用精确计数决定是否真的升热。</p>
 *
 * <h2>为什么是分片而不是两个计数器</h2>
 * <p>两桶方案（当前桶 + 上一桶）在桶边界会让计数瞬间腰斩，同一个 key 在边界前后可能反复升降。
 * 分片环形数组把窗口切成 N 份，读数时求和最近一整窗的分片，边界只掉 1/N，不会阶跃。</p>
 *
 * <h2>为什么用 synchronized</h2>
 * <p>窗口是<strong>每个 key 一个</strong>的，锁的争用只发生在"同一个热 key 的并发读"上，
 * 而那种场景本来就会在别处串行化（单飞/存储锁）。用分段原子数组换来的复杂度
 * 与这里的实际争用不成比例。</p>
 *
 * @author Asial Jim
 */
final class HotWindow {

    private final long[] counts;
    private final long[] stamps;
    private final long sliceMillis;
    private final int slices;

    /**
     * @param slices      分片数（≥ 2）
     * @param windowMillis 统计窗口（毫秒）
     */
    HotWindow(int slices, long windowMillis) {
        int n = Math.max(2, slices);
        this.slices = n;
        this.counts = new long[n];
        this.stamps = new long[n];
        this.sliceMillis = Math.max(1L, windowMillis / n);
    }

    /**
     * 建表时把"建表前已攒的访问量"写入当前时间片。
     *
     * <p>不做这一步的话，建表瞬间计数会从 1 重新开始——低速热点永远攒不够一个完整窗口，
     * 表现为"明明访问了 N 次却迟迟不升热"。调用方传入的 {@code count} 应已扣除紧随其后的
     * 那次 {@link #incrementAndCount}，否则会多算一次。</p>
     *
     * @param nowMillis 当前毫秒时间
     * @param count     初始计数（≥ 0）
     */
    synchronized void seed(long nowMillis, long count) {
        long currentSlice = nowMillis / sliceMillis;
        int slot = slotOf(currentSlice);
        stamps[slot] = currentSlice;
        counts[slot] = Math.max(0L, count);
    }

    /**
     * 记一次访问并返回<strong>最近一整窗</strong>的累计次数。
     *
     * @param nowMillis 当前毫秒时间
     * @return 窗口内累计次数
     */
    long incrementAndCount(long nowMillis) {
        long currentSlice = nowMillis / sliceMillis;
        synchronized (this) {
            int slot = slotOf(currentSlice);
            if (stamps[slot] != currentSlice) {
                // 这一格属于更早的时间片 → 先清掉再加入本次
                counts[slot] = 0L;
                stamps[slot] = currentSlice;
            }
            counts[slot]++;
            return sumWithinWindow(currentSlice);
        }
    }

    /**
     * 读取最近一整窗的累计次数（<strong>不</strong>增加计数）。
     *
     * @param nowMillis 当前毫秒时间
     * @return 窗口内累计次数
     */
    long count(long nowMillis) {
        long currentSlice = nowMillis / sliceMillis;
        synchronized (this) {
            return sumWithinWindow(currentSlice);
        }
    }

    /**
     * 只累加"最近 {@code slices} 个时间片"的计数；更早的格子视为过期，不参与求和
     * （也不必主动清零——下次写入到同一格时会清）。
     */
    private long sumWithinWindow(long currentSlice) {
        long oldest = currentSlice - slices + 1;
        long total = 0L;
        for (int i = 0; i < slices; i++) {
            if (stamps[i] >= oldest) {
                total += counts[i];
            }
        }
        return total;
    }

    private int slotOf(long slice) {
        return (int) Math.floorMod(slice, (long) slices);
    }
}
