package com.asialjim.cache4j.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Spring {@link CacheErrorHandler}：把 cache4j 的降级策略接到 Spring 的缓存拦截器上。
 *
 * <ul>
 *   <li>{@code failOpen=true}（默认）：记录 WARN 并吞掉异常，业务继续；</li>
 *   <li>{@code failOpen=false}：原样抛出；</li>
 *   <li><strong>编解码 / key 编码错误永远不吞</strong>：那是数据错误或编程错误，必须可见。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public final class Cache4jCacheErrorHandler implements CacheErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Cache4jCacheErrorHandler.class);

    private final boolean failOpen;

    /**
     * @param failOpen 是否降级
     */
    public Cache4jCacheErrorHandler(boolean failOpen) {
        this.failOpen = failOpen;
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        handle(exception, "get", cache);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        handle(exception, "put", cache);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        handle(exception, "evict", cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        handle(exception, "clear", cache);
    }

    private void handle(RuntimeException exception, String operation, Cache cache) {
        if (isDataOrProgramError(exception)) {
            throw exception;
        }
        if (!failOpen) {
            throw exception;
        }
        String namespace = cache == null ? "-" : cache.getName();
        LOG.warn("[{}]-{}-{}", "Spring缓存桥接", "降级", "namespace=" + namespace
                + "，操作=" + operation + "，按 failOpen 降级，原因="
                + exception.getClass().getSimpleName() + ":" + exception.getMessage());
    }

    private static boolean isDataOrProgramError(RuntimeException exception) {
        if (exception instanceof CacheException cacheException
                && cacheException.resCode() instanceof CacheResCode code) {
            return code == CacheResCode.CODEC_ENCODE_FAILED
                    || code == CacheResCode.CODEC_DECODE_FAILED
                    || code == CacheResCode.KEY_ENCODE_FAILED
                    || code == CacheResCode.INVALID_SPEC;
        }
        return false;
    }
}
