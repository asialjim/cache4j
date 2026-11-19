package com.asialjim.cache4j.core;

import com.asialjim.standard4j.exception.ResCode;
import com.asialjim.standard4j.exception.SystemException;

/**
 * 缓存基础设施异常。
 *
 * <p>缓存故障属系统侧问题，继承 standard4j 的 {@link SystemException}；loader 内部的业务异常
 * 保持原类型，不被本异常包装。</p>
 *
 * <p>编解码错误与 key 编码错误<strong>永远不降级</strong>：它们是数据/编程错误，不是可用性问题。</p>
 *
 * @author Asial Jim
 */
public class CacheException extends SystemException {

    private static final long serialVersionUID = 1L;

    /**
     * @param resCode 错误码
     */
    public CacheException(ResCode resCode) {
        super(resCode);
    }

    /**
     * @param resCode 错误码
     * @param message 自定义消息
     */
    public CacheException(ResCode resCode, String message) {
        super(resCode, message);
    }

    /**
     * @param resCode 错误码
     * @param message 自定义消息
     * @param cause   原因
     */
    public CacheException(ResCode resCode, String message, Throwable cause) {
        super(resCode, message, cause);
    }
}
