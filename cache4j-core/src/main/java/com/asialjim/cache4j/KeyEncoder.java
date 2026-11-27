package com.asialjim.cache4j;

/**
 * key 编码 SPI：把任意 key 转成确定性、单射、长度有界、跨进程稳定的字符串。
 *
 * <p>实现必须线程安全、确定性：不得引入随机数、时间戳或对象身份依赖。</p>
 *
 * @author Asial Jim
 */
@FunctionalInterface
public interface KeyEncoder {

    /**
     * 编码单个 key。
     *
     * @param key key，允许为 {@code null}
     * @return 规范编码
     */
    String encode(Object key);

    /**
     * 编码多参数 key（等价于把参数当作数组编码）。
     *
     * @param parts 参数
     * @return 规范编码
     */
    default String encodeAll(Object... parts) {
        return encode(parts);
    }
}
