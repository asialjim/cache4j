package com.asialjim.cache4j.localcaffeine;

import com.asialjim.cache4j.core.local.LocalCache;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.local.LocalCacheContractTest;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Caffeine 实现跑与 HashMap 完全相同的契约测试。
 *
 * @author Asial Jim
 */
@DisplayName("CaffeineLocalCache")
class CaffeineLocalCacheTest extends LocalCacheContractTest {

    @Override
    protected LocalCache<String> newCache(long maxSize) {
        LocalCacheSpec spec = LocalCacheSpec.staticCache("user:detail", maxSize, Duration.ofSeconds(30));
        return CaffeineLocalCaches.create(spec, null, clock::nanos);
    }

    @Override
    protected String implementation() {
        return "CaffeineLocalCache";
    }
}
