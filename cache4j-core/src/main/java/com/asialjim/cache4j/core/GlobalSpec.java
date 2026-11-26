package com.asialjim.cache4j.core;

import java.time.Duration;

/**
     * 中央（集群级）阈值。
     *
     * @param window           中央窗口
     * @param hotThreshold     集群级升热阈值
     * @param coolThreshold    集群级降热阈值（必须小于升热阈值，形成滞回）
     * @param demoteWindows    连续低于冷阈值的窗口数
     * @param staleWindows     无上报多少个窗口后判 {@code COOLING}
     * @param clientEstimate   连接同一套中央缓存的客户端实例数（推导预选阈值）
     * @author Asial Jim
     */
    public record GlobalSpec(
            Duration window,
            long hotQps,
            long coolQps,
            int demoteWindows,
            int staleWindows,
            int clientEstimate
    ) {

        /**
         * @return 判定用的窗口内累计次数阈值（由 QPS 换算）
         */
        public long hotCount() {
            return hotQps * window.getSeconds();
        }

        /**
         * @return 冷却判定用的窗口内累计次数阈值（由 QPS 换算）
         */
        public long coolCount() {
            return coolQps * window.getSeconds();
        }

        /**
         * 构造并补充常用的派生默认值（冷阈值取热阈值的 1/4）。
         *
         * @param window         中央窗口
         * @param hotQps         升热阈值（QPS）
         * @param clientEstimate 客户端实例数
         * @return 配置
         */
        public static GlobalSpec of(Duration window, long hotQps, int clientEstimate) {
            return new GlobalSpec(window, hotQps, Math.max(1L, hotQps / 4L), 3, 3, clientEstimate);
        }

        /**
         * 校验。
         *
         * @return this
         */
        public GlobalSpec validate() {
            require(window != null && !window.isZero() && !window.isNegative(), "global.window 必须为正");
            require(hotQps > 0L, "global.hotQps 必须为正");
            require(coolQps >= 0L && coolQps < hotQps,
                    "要求 0 <= coolQps < hotQps");
            require(demoteWindows >= 1, "global.demoteWindows 必须 >= 1");
            require(staleWindows >= 1, "global.staleWindows 必须 >= 1");
            require(clientEstimate >= 1, "global.clientEstimate 必须 >= 1");
            return this;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw invalid(message);
            }
        }
    
    /**
     * @param message 消息
     * @return 规格异常
     */
    static com.asialjim.cache4j.core.CacheException invalid(String message) {
        return new com.asialjim.cache4j.core.CacheException(
                com.asialjim.cache4j.core.CacheResCode.INVALID_SPEC, message);
    }
}

