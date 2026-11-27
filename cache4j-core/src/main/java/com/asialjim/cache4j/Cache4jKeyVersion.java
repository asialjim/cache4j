package com.asialjim.cache4j;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@AllArgsConstructor
public abstract class Cache4jKeyVersion implements Supplier<Long> {
    @Getter
    private final String key;

    public static final Cache4jKeyVersion NULL_VERSION_KEY = defaultValueOf("", () -> null);

    public static Cache4jKeyVersion defaultValueOf(String key, Supplier<Long> version) {
        return new Cache4jKeyVersion(key) {
            @Override
            public Long get() {
                if (Objects.nonNull(version))
                    return version.get();
                return 0L;
            }
        };
    }
}
