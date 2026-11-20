package com.asialjim.cache4j.spring;

import com.asialjim.cache4j.core.KeyEncoder;
import com.asialjim.cache4j.core.central.CentralCacheProvider;
import com.asialjim.cache4j.core.strategy.CacheDefinition;
import com.asialjim.cache4j.core.strategy.CacheStrategyDecl;
import com.asialjim.cache4j.local.LocalCacheProvider;
import org.springframework.beans.factory.FactoryBean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把不可变的 {@link Cache4jSpringOptions} 变成 setter 友好的 Bean，供
 * <strong>XML 装配</strong>（{@code spring-context.xml}）使用。
 *
 * <p>{@code Cache4jSpringOptions} 是刻意做成 builder + 不可变的（装配期一次性定下来，
 * 避免运行期被改）。XML 没有 builder 语法，所以用 {@link FactoryBean} 桥一下——
 * 这也是 Spring 自己的标准做法。</p>
 *
 * <p>Java Config 用户不需要本类，直接 {@code Cache4jSpringOptions.builder()} 即可。</p>
 *
 * @author Asial Jim
 */
public class Cache4jOptionsFactoryBean implements FactoryBean<Cache4jSpringOptions> {

    private boolean enabled = true;

    private KeyEncoder keyEncoder;

    private LocalCacheProvider localProvider;

    private CentralCacheProvider centralProvider;

    private boolean ownsWhenClosing = false;

    private boolean statsEnabled = false;

    private boolean undeclaredEnabled = true;

    private List<CacheDefinition> caches = new ArrayList<>();

    private List<String> declPackages = new ArrayList<>();

    private List<Class<? extends CacheStrategyDecl>> declEnums = new ArrayList<>();

    @Override
    public Cache4jSpringOptions getObject() {
        Cache4jSpringOptions.Builder builder = Cache4jSpringOptions.builder()
                .enabled(enabled)
                .statsEnabled(statsEnabled)
                .undeclaredEnabled(undeclaredEnabled)
                .declPackages(declPackages)
                // 关闭时由配置文件声明自己的生命周期
                .centralProvider(centralProvider, ownsWhenClosing);
        if (keyEncoder != null) {
            builder.keyEncoder(keyEncoder);
        }
        if (localProvider != null) {
            builder.localProvider(localProvider);
        }
        if (caches != null && !caches.isEmpty()) {
            builder.caches(caches);
        }
        if (declEnums != null && !declEnums.isEmpty()) {
            builder.declEnums(declEnums.toArray(new Class<?>[0]));
        }
        return builder.build();
    }

    @Override
    public Class<?> getObjectType() {
        return Cache4jSpringOptions.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setKeyEncoder(KeyEncoder keyEncoder) {
        this.keyEncoder = keyEncoder;
    }

    public void setLocalProvider(LocalCacheProvider localProvider) {
        this.localProvider = localProvider;
    }

    public void setCentralProvider(CentralCacheProvider centralProvider) {
        this.centralProvider = centralProvider;
    }

    public void setOwnsWhenClosing(boolean ownsWhenClosing) {
        this.ownsWhenClosing = ownsWhenClosing;
    }

    public void setStatsEnabled(boolean statsEnabled) {
        this.statsEnabled = statsEnabled;
    }

    public void setUndeclaredEnabled(boolean undeclaredEnabled) {
        this.undeclaredEnabled = undeclaredEnabled;
    }

    public void setCaches(List<CacheDefinition> caches) {
        this.caches = caches == null ? new ArrayList<>() : caches;
    }

    public void setDeclPackages(List<String> declPackages) {
        this.declPackages = declPackages == null ? new ArrayList<>() : declPackages;
    }

    public void setDeclEnums(List<Class<? extends CacheStrategyDecl>> declEnums) {
        this.declEnums = declEnums == null ? new ArrayList<>() : declEnums;
    }

    /**
     * 便捷：从一个逗号分隔的包名列表设置扫描范围（XML 里写 {@code value} 更省事）。
     *
     * @param packages 逗号分隔的包名
     */
    public void setDeclPackagesAsString(String packages) {
        this.declPackages = packages == null || packages.isBlank()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.stream(packages.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList());
    }
}
