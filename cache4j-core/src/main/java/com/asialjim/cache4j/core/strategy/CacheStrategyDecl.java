package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.local.LocalStrategy;

/**
 * 缓存策略声明：业务方用一个枚举实现它，库负责发现、翻译、校验、注册。
 *
 * <p><strong>契约只有三个成员，全部必需</strong>：命名空间、初始本地策略、用途说明。
 * 容量、过期、版本对账、热升阈值等参数<strong>不在这里</strong>——它们归策略仓库
 * （由运维按 namespace 独立配置），声明只表达"要一个叫什么、起初用什么策略、干什么用"的缓存。</p>
 *
 * <p>典型写法（配合 Lombok {@code @Getter}，只需声明三个字段）：</p>
 *
 * <pre>{@code
 * @Getter
 * @CacheNamespace
 * public enum UserCacheDecls implements CacheStrategyDecl {
 *
 *     USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情，读多写少"),
 *     USER_PROFILE("user:profile", LocalStrategy.DYNAMIC, "用户档案，存在热点"),
 *     ORDER_SNAPSHOT("order:snapshot", LocalStrategy.NONE, "订单快照，仅中央");
 *
 *     private final String namespace;
 *     private final LocalStrategy strategy;
 *     private final String description;
 *
 *     UserCacheDecls(String namespace, LocalStrategy strategy, String description) { ... }
 * }
 * }</pre>
 *
 * <p><strong>实现约束</strong>：</p>
 * <ul>
 *   <li>必须声明为 {@code enum}（库用 {@link Class#getEnumConstants()} 取常量，不做反射构造）；</li>
 *   <li>常量必须不可变，静态块<strong>不得有副作用</strong>（不许在其中注册或读外部配置），
 *       否则注册时机取决于类初始化顺序；</li>
 *   <li>一个枚举可声明多个 namespace；不同枚举声明同一 namespace 属于冲突，启动失败。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public interface CacheStrategyDecl {

    /**
     * @return 缓存命名空间（例如 {@code user:detail}）；允许 {@code [A-Za-z0-9._:-]}，长度 ≤ 64
     */
    String getNamespace();

    /**
     * @return 初始本地策略（<strong>声明值</strong>，可被策略仓库的运行期配置覆盖）
     */
    LocalStrategy getStrategy();

    /**
     * @return 用途说明；<strong>必须非空</strong>，运维要据此判断这个缓存是干什么用的
     */
    String getDescription();
}
