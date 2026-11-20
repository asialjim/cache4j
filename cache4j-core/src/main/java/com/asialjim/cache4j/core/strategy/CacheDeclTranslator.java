package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.log.CacheLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把一个枚举 {@code Class} 翻译成一串候选声明。
 *
 * <p>这是"声明 → 可用配置"的唯一转换点，链路顺序不可调换：</p>
 * <ol>
 *   <li>类型校验：{@code isAssignableFrom} → {@code isEnum}；</li>
 *   <li>一次取全部常量：{@link Class#getEnumConstants()}（<strong>不是反射构造</strong>）；</li>
 *   <li>逐条校验：命名空间、策略、说明（错误信息精确到 {@code 枚举#常量名}）；</li>
 *   <li>去重与冲突由仓库统一处理（本类只负责翻译与单条校验）。</li>
 * </ol>
 *
 * <p><strong>关于 {@code getEnumConstants()}</strong>：它读的是 {@code Class} 内部由 JVM 建好的
 * 常量数组，不受"枚举禁止反射实例化"的限制（{@code Constructor#newInstance} 对枚举会抛
 * {@code IllegalArgumentException: Cannot reflectively create enum objects}）；
 * 对非枚举类返回 {@code null}（不是空数组），对没有常量的枚举返回长度 0 的数组——两者语义不同。</p>
 *
 * @author Asial Jim
 */
public final class CacheDeclTranslator {

    /**
     * 描述信息最大长度。
     */
    public static final int MAX_DESCRIPTION_LENGTH = 128;

    /**
     * namespace 最大长度（与 {@code CacheKey} 保持一致）。
     */
    public static final int MAX_NAMESPACE_LENGTH = 64;

    private static final CacheLogger LOG = CacheLogger.get(CacheDeclTranslator.class);

    /**
     * 翻译一个枚举类。
     *
     * @param type  枚举类
     * @param where 发现来源（用于日志与报错），例如 {@code META-INF/services：…} 或 {@code Spring 扫描：com.acme}
     * @return 候选声明；空枚举返回空列表
     */
    public List<CacheCandidate> translate(Class<?> type, String where) {
        // ① 类型校验：顺序固定，报错才有信息量
        if (!CacheStrategyDecl.class.isAssignableFrom(type)) {
            throw invalid(where + "：" + type.getName() + " 未实现 CacheStrategyDecl，实际接口="
                    + Arrays.toString(type.getInterfaces()));
        }
        if (!type.isEnum()) {
            // 伪枚举（普通类 + 静态常量）无法表达策略集合：getEnumConstants() 对它返回 null
            throw invalid(where + "：" + type.getName()
                    + " 不是枚举（请声明为 enum，或用静态常量列表由调用方显式传入）");
        }

        // ② 取全部常量：Class 内建能力，不做反射构造
        CacheStrategyDecl[] constants = type.asSubclass(CacheStrategyDecl.class).getEnumConstants();
        if (constants == null) {
            // 防御：isEnum() 已挡，理论上不可达
            throw invalid(where + "：" + type.getName() + " 无法读取枚举常量");
        }
        if (constants.length == 0) {
            LOG.debug(CacheLogger.Subject.ASSEMBLY, "声明", where + "：" + type.getName() + " 无任何常量，跳过");
            return List.of();
        }

        // ③ 逐条校验 + 翻译
        List<CacheCandidate> out = new ArrayList<>(constants.length);
        for (CacheStrategyDecl decl : constants) {
            // 用 name()：toString() 可被覆写、ordinal() 会随常量顺序漂移
            String constantName = ((Enum<?>) decl).name();
            String origin = type.getName() + "#" + constantName + " (" + where + ")";
            String namespace = requireNamespace(decl.getNamespace(), origin);
            if (decl.getStrategy() == null) {
                throw invalid(origin + "：getStrategy() 不能为 null");
            }
            requireDescription(decl.getDescription(), origin);

            CacheDefinition definition = BuiltinDefaults.definitionOf(namespace, decl.getStrategy());
            out.add(new CacheCandidate(origin, definition));
            LOG.debug(CacheLogger.Subject.ASSEMBLY, "声明", origin
                    + "：strategy=" + decl.getStrategy() + "，description=" + decl.getDescription());
        }
        return out;
    }

    /**
     * 校验 namespace（与 {@code CacheKey} 同一套规则）。
     *
     * @param namespace 命名空间
     * @param origin    来源（用于报错）
     * @return 命名空间本体
     */
    private String requireNamespace(String namespace, String origin) {
        if (namespace == null || namespace.isBlank()) {
            throw invalid(origin + "：getNamespace() 不能为空");
        }
        if (namespace.length() > MAX_NAMESPACE_LENGTH) {
            throw invalid(origin + "：namespace 长度不能超过 " + MAX_NAMESPACE_LENGTH + "：" + namespace);
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-';
            if (!allowed) {
                throw invalid(origin + "：namespace 只允许 [A-Za-z0-9._:-]，非法字符 '" + c + "'：" + namespace);
            }
        }
        return namespace;
    }

    /**
     * 校验描述：非空 + 限长 + 禁控制字符（描述会进日志，控制字符可伪造日志行）。
     *
     * @param description 描述
     * @param origin      来源（用于报错）
     */
    private void requireDescription(String description, String origin) {
        if (description == null || description.isBlank()) {
            throw invalid(origin + "：getDescription() 不能为空——运维要据此判断这个缓存的用途");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw invalid(origin + "：description 长度不能超过 " + MAX_DESCRIPTION_LENGTH
                    + "，实际 " + description.length());
        }
        for (int i = 0; i < description.length(); i++) {
            if (Character.isISOControl(description.charAt(i))) {
                throw invalid(origin + "：description 不允许包含控制字符（会污染日志）");
            }
        }
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.INVALID_SPEC, message);
    }
}
