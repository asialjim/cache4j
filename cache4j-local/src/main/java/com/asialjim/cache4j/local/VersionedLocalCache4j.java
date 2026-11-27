package com.asialjim.cache4j.local;

import com.asialjim.cache4j.Cache4jKeyVersionDetector;
import com.asialjim.cache4j.level.LocalCache4j;

public interface VersionedLocalCache4j<V> extends LocalCache4j<V>, Cache4jKeyVersionDetector {
}
