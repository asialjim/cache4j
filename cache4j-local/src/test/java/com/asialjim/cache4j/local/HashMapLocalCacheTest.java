package com.asialjim.cache4j.local;

import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * 默认 HashMap 实现跑契约测试。
 *
 * @author Asial Jim
 */
@DisplayName("HashMapLocalCache")
class HashMapLocalCacheTest extends LocalCacheContractTest {

    @Override
    protected LocalCache<String> newCache(long maxSize) {
        LocalCacheSpec spec = LocalCacheSpec.staticCache("user:detail", maxSize, Duration.ofSeconds(30));
        return LocalCaches.create(spec, null, clock::nanos);
    }

    @Override
    protected String implementation() {
        return "HashMapLocalCache";
    }
}
