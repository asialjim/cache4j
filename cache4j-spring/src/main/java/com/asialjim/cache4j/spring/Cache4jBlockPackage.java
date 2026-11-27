package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.strategy.CacheBlock;
import com.asialjim.cache4j.strategy.CacheBlockTranslator;
import com.asialjim.cache4j.strategy.CacheStrategyProperty;
import com.asialjim.cache4j.strategy.CacheStrategyReader;

import java.util.*;

public class Cache4jBlockPackage {
    private static final CacheBlockTranslator translator = CacheBlockTranslator.INSTANCE;
    private final Set<Class<? extends CacheBlock>> classes;
    private final CacheStrategyReader cacheStrategyReader;

    public Cache4jBlockPackage(Set<String> packages, CacheStrategyReader cacheStrategyReader) {
        this.cacheStrategyReader = cacheStrategyReader;
        this.classes = new HashSet<>();
        List<Class<? extends CacheBlock>> scan = CacheBlockScanner.scan(packages);
        this.classes.addAll(scan);
    }

    @SuppressWarnings("unused")
    public void init(){
        for (CacheStrategyProperty property : cacheStrategyDecls()) {
            cacheStrategyReader.registerIfAbsent(property);
        }
    }

    private List<CacheStrategyProperty> cacheStrategyDecls() {
        List<CacheStrategyProperty> list = new ArrayList<>();
        for (Class<? extends CacheBlock> aClass : classes) {
            if (!aClass.isEnum())
                continue;
            CacheBlock[] enumConstants = aClass.getEnumConstants();
            for (CacheBlock block : enumConstants) {
                if (Objects.isNull(block))
                    continue;
                CacheStrategyProperty translate = translator.translate(block);
                list.add(translate);
            }
        }
        return list;
    }
}
