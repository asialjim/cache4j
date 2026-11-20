package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.CacheKey;

import java.util.function.LongSupplier;

/**
 * 双桶 Count-Min Sketch：估算滑动窗口内的<strong>读</strong>频次。
 *
 * <p>结构：</p>
 * <ul>
 *   <li>两个桶 {@code current} / {@code previous}，每 {@code window} 旋转一次
 *       （{@code previous = current}，{@code current = new}）；</li>
 *   <li>估计值 = {@code current.estimate + previous.estimate}；</li>
 *   <li>每格 <strong>16 位</strong>计数器（0–65 535），宽度为 2 的幂（由 {@code sketchWidthBits} 配置），
 *       深度 ≥ 2，取多行最小值降低碰撞高估。</li>
 * </ul>
 *
 * <p>内存固定：{@code 2 * width * depth * 2} 字节（16 位；width=4096、depth=4 → 64KB），
 * 不随 key 基数增长。Count-Min 只会<strong>高估</strong>——在本库里这是<strong>安全方向</strong>：
 * 它只用于"候选预选"，候选阶段宁可多进几个，也不能漏掉热点。</p>
 *
 * <p><strong>它不参与升热判定</strong>：升热由每 key 的滑动窗口精确计数决定（8 位饱和与
 * "只会高估"这两点在升热阶段都会出问题）。</p>
 *
 * @author Asial Jim
 */
final class FrequencySketch {

    /**
     * 单格计数上限（16 位无符号）。
     *
     * <p>8 位（255）在"候选阈值 = 25 QPS × 5s = 125 次"时看似够用，但阈值一调高就饱和，
     * 饱和后无法区分"刚过阈值"和"远超阈值"；32 位则内存翻四倍、工作集挤出 L2 舒适区，
     * 换来的上限永远用不到。16 位（65 535 次/窗口 ≈ 13 107 QPS）是甜点。</p>
     */
    private static final int MAX_COUNT = 65_535;

    private final int width;
    private final int mask;
    private final int depth;
    private final long windowNanos;
    private final LongSupplier nanoClock;

    private short[][] current;
    private short[][] previous;
    private long nextRotateAtNanos;

    FrequencySketch(int width, int depth, long windowNanos, long now, LongSupplier nanoClock) {
        this.width = width;
        this.mask = width - 1;
        this.depth = depth;
        this.windowNanos = windowNanos;
        this.nanoClock = nanoClock;
        this.current = allocate();
        this.previous = allocate();
        this.nextRotateAtNanos = now + windowNanos;
    }

    private short[][] allocate() {
        short[][] table = new short[depth][];
        for (int i = 0; i < depth; i++) {
            table[i] = new short[width];
        }
        return table;
    }

    /**
     * 窗口旋转（惰性，访问路径触发）。
     *
     * @param now 当前纳秒
     */
    void rotateIfNeeded(long now) {
        if (now - nextRotateAtNanos < 0L) {
            return;
        }
        previous = current;
        current = allocate();
        nextRotateAtNanos = now + windowNanos;
    }

    /**
     * 累加一次访问并返回双窗口估计值。
     *
     * @param key key
     * @param now 当前纳秒
     * @return 估计频次
     */
    int incrementAndEstimate(CacheKey key, long now) {
        rotateIfNeeded(now);
        int hash = spread(key.logical().hashCode());
        increment(hash);
        return estimate(current, hash) + estimate(previous, hash);
    }

    /**
     * 只读估算，不累加。
     *
     * @param key key
     * @param now 当前纳秒
     * @return 估计频次
     */
    int estimate(CacheKey key, long now) {
        rotateIfNeeded(now);
        int hash = spread(key.logical().hashCode());
        return estimate(current, hash) + estimate(previous, hash);
    }

    private void increment(int hash) {
        for (int row = 0; row < depth; row++) {
            int index = index(hash, row);
            if ((current[row][index] & 0xFFFF) < MAX_COUNT) {
                current[row][index]++;
            }
        }
    }

    private int estimate(short[][] table, int hash) {
        int min = MAX_COUNT;
        for (int row = 0; row < depth; row++) {
            int value = table[row][index(hash, row)] & 0xFFFF;
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    private int index(int hash, int row) {
        return (hash ^ (row * 0x9E3779B9)) & mask;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    /**
     * @return 当前窗口起点（纳秒），用于观测
     */
    long nextRotateAtNanos() {
        return nextRotateAtNanos;
    }

    /**
     * @return 草图固定内存占用（字节）
     */
    long estimatedBytes() {
        return 2L * width * depth;
    }

    /**
     * @return 当前纳秒
     */
    long now() {
        return nanoClock.getAsLong();
    }
}
