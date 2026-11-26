package com.asialjim.cache4j.core;

/**
 * 热 key 协调方式。
 *
 * @author Asial Jim
 */
public enum Coordination {
    /**
     * 单 JVM：本地升降热，不访问中央热度。
     */
    LOCAL,
    /**
     * 多节点：本地只升不降，降热由中央裁决；必须提供 {@code HotKeyCoordinator}。
     */
    GLOBAL
}
