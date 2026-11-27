package com.asialjim.cache4j.local;

import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.level.StaticLocalCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;

import java.util.Map;
import java.util.Set;

public abstract class AbstractStaticLocalCache4j<V> extends AbstractVersionedLocalCache4j<V> implements StaticLocalCache4j<V> {
    public AbstractStaticLocalCache4j(Cache4jKeyVersionDetector centralCache4jKeyVersionDetector) {
        super(centralCache4jKeyVersionDetector);
    }

    @Override
    public Map<String, Long> partitionVersion(Set<String> key, CacheStrategy strategy) {
        return super.centralCache4jKeyVersionDetector.partitionVersion(key, strategy);
    }
}
