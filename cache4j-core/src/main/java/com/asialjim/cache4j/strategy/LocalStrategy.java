package com.asialjim.cache4j.strategy;

/**
 * 本地缓存策略。
 *
 * @author Asial Jim
 */
public enum LocalStrategy {

    /**
     * 本地不存值，读请求直接落到中央缓存；中央命中的值也不回填本地。
     */
    NONE,

    /**
     * 本地优先：命中直接返回，未命中查中央并回填；本地有容量与 TTL 淘汰。
     */
    STATIC,

    /**
     * 只让高频（热点）key 进入本地：本地窗口频次达标立即升热；本地不升热时由中央补偿查询；
     * 降热只认中央 {@code COOLING}。
     */
    DYNAMIC
}
