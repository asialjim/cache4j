package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.local.LocalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明翻译链路：类型校验 → 取枚举常量 → 逐条校验 → 产出候选。
 *
 * <p>覆盖 {@code getEnumConstants()} 与 {@code isEnum()} 的几个典型坑。</p>
 *
 * @author Asial Jim
 */
class CacheDeclTranslatorTest {

    private final CacheDeclTranslator translator = new CacheDeclTranslator();

    // ------------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("翻译枚举的全部常量，顺序与声明一致，参数走内建默认")
    void translatesAllConstantsInDeclarationOrder() {
        List<CacheCandidate> candidates = translator.translate(GoodDecls.class, "测试");

        assertEquals(3, candidates.size());
        assertEquals("user:detail", candidates.get(0).namespace());
        assertEquals(LocalStrategy.STATIC, candidates.get(0).definition().strategy().localStrategy());
        assertEquals(LocalStrategy.DYNAMIC, candidates.get(1).definition().strategy().localStrategy());
        assertEquals(LocalStrategy.NONE, candidates.get(2).definition().strategy().localStrategy());

        // 内建默认：STATIC 有 maxSize/TTL，NONE 没有
        assertEquals(BuiltinDefaults.MAX_SIZE, candidates.get(0).definition().strategy().localMaxSize());
        assertEquals(BuiltinDefaults.CENTRAL_NULL_TTL, candidates.get(0).definition().strategy().centralNullTtl());
        assertEquals(0L, candidates.get(2).definition().strategy().localMaxSize());
    }

    @Test
    @DisplayName("来源标识使用 name()，不受 toString() 覆写影响")
    void originUsesEnumNameNotToString() {
        List<CacheCandidate> candidates = translator.translate(OverridingToStringDecls.class, "测试");

        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).origin().contains("#REAL_NAME"),
                "来源应为枚举 name()，实际=" + candidates.get(0).origin());
        assertTrue(!candidates.get(0).origin().contains("我是被覆写的toString"),
                "不得使用 toString()，实际=" + candidates.get(0).origin());
    }

    @Test
    @DisplayName("空枚举：返回空列表，不报错")
    void emptyEnumYieldsEmptyList() {
        assertTrue(translator.translate(EmptyDecls.class, "测试").isEmpty());
    }

    // ------------------------------------------------------------------ 类型校验

    @Test
    @DisplayName("未实现 CacheStrategyDecl：报错并列出实际接口")
    void rejectsNonDeclType() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(String.class, "测试"));
        assertTrue(e.getMessage().contains("未实现 CacheStrategyDecl"), e.getMessage());
    }

    @Test
    @DisplayName("伪枚举（普通类 + 静态常量）：报错而不是返回空")
    void rejectsPseudoEnum() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(PseudoDecls.class, "测试"));
        assertTrue(e.getMessage().contains("不是枚举"), e.getMessage());
    }

    // ------------------------------------------------------------------ 单条校验

    @Test
    @DisplayName("描述为空：报错（运维要据此判断用途）")
    void rejectsBlankDescription() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(BlankDescriptionDecls.class, "测试"));
        assertTrue(e.getMessage().contains("getDescription() 不能为空"), e.getMessage());
        assertTrue(e.getMessage().contains("#ITEM"), "错误信息应定位到具体常量：" + e.getMessage());
    }

    @Test
    @DisplayName("描述超长：报错")
    void rejectsTooLongDescription() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(LongDescriptionDecls.class, "测试"));
        assertTrue(e.getMessage().contains("description 长度不能超过"), e.getMessage());
    }

    @Test
    @DisplayName("描述含控制字符：报错（防日志注入）")
    void rejectsControlCharacterInDescription() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(ControlCharDescriptionDecls.class, "测试"));
        assertTrue(e.getMessage().contains("控制字符"), e.getMessage());
    }

    @Test
    @DisplayName("namespace 含非法字符：报错")
    void rejectsIllegalNamespace() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(IllegalNamespaceDecls.class, "测试"));
        assertTrue(e.getMessage().contains("namespace 只允许"), e.getMessage());
    }

    @Test
    @DisplayName("namespace 为空：报错")
    void rejectsBlankNamespace() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(BlankNamespaceDecls.class, "测试"));
        assertTrue(e.getMessage().contains("getNamespace() 不能为空"), e.getMessage());
    }

    @Test
    @DisplayName("strategy 为 null：报错")
    void rejectsNullStrategy() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(NullStrategyDecls.class, "测试"));
        assertTrue(e.getMessage().contains("getStrategy() 不能为 null"), e.getMessage());
    }

    @Test
    @DisplayName("namespace 超长：报错")
    void rejectsTooLongNamespace() {
        CacheException e = assertThrows(CacheException.class,
                () -> translator.translate(LongNamespaceDecls.class, "测试"));
        assertTrue(e.getMessage().contains("namespace 长度不能超过"), e.getMessage());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * 正常声明。
     */
    public enum GoodDecls implements CacheStrategyDecl {

        /**
         * 静态。
         */
        USER_DETAIL("user:detail", LocalStrategy.STATIC, "用户详情"),

        /**
         * 动态。
         */
        USER_PROFILE("user:profile", LocalStrategy.DYNAMIC, "用户档案"),

        /**
         * 仅中央。
         */
        ORDER_SNAPSHOT("order:snapshot", LocalStrategy.NONE, "订单快照");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        GoodDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 覆写 toString 的声明：来源标识必须仍用 name()。
     */
    public enum OverridingToStringDecls implements CacheStrategyDecl {

        /**
         * 常量名与展示名刻意不同。
         */
        REAL_NAME("x:y", LocalStrategy.STATIC, "用于验证 name() 而非 toString()") {
            @Override
            public String toString() {
                return "我是被覆写的toString";
            }
        };

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        OverridingToStringDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 空枚举：没有任何常量，但方法仍需有实现（常量列表后声明）。
     */
    public enum EmptyDecls implements CacheStrategyDecl {

        ;

        @Override
        public String getNamespace() {
            throw new UnsupportedOperationException("空枚举不会被翻译");
        }

        @Override
        public LocalStrategy getStrategy() {
            throw new UnsupportedOperationException("空枚举不会被翻译");
        }

        @Override
        public String getDescription() {
            throw new UnsupportedOperationException("空枚举不会被翻译");
        }
    }

    /**
     * 伪枚举：普通类 + 静态常量。
     */
    public static final class PseudoDecls implements CacheStrategyDecl {

        /**
         * 唯一实例。
         */
        public static final PseudoDecls INSTANCE = new PseudoDecls();

        @Override
        public String getNamespace() {
            return "p:x";
        }

        @Override
        public LocalStrategy getStrategy() {
            return LocalStrategy.STATIC;
        }

        @Override
        public String getDescription() {
            return "伪枚举";
        }
    }

    /**
     * 描述为空。
     */
    public enum BlankDescriptionDecls implements CacheStrategyDecl {

        /**
         * 描述为空白。
         */
        ITEM("a:b", LocalStrategy.STATIC, "   ");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        BlankDescriptionDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 描述超长。
     */
    public enum LongDescriptionDecls implements CacheStrategyDecl {

        /**
         * 描述 200 字符。
         */
        ITEM("a:b", LocalStrategy.STATIC, "x".repeat(CacheDeclTranslator.MAX_DESCRIPTION_LENGTH + 1));

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        LongDescriptionDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 描述含控制字符。
     */
    public enum ControlCharDescriptionDecls implements CacheStrategyDecl {

        /**
         * 含换行符。
         */
        ITEM("a:b", LocalStrategy.STATIC, "第一行\n伪造日志行");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        ControlCharDescriptionDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * namespace 含非法字符。
     */
    public enum IllegalNamespaceDecls implements CacheStrategyDecl {

        /**
         * 含空格。
         */
        ITEM("bad namespace", LocalStrategy.STATIC, "非法命名空间");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        IllegalNamespaceDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * namespace 为空。
     */
    public enum BlankNamespaceDecls implements CacheStrategyDecl {

        /**
         * 空命名空间。
         */
        ITEM("", LocalStrategy.STATIC, "空命名空间");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        BlankNamespaceDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * 策略为 null。
     */
    public enum NullStrategyDecls implements CacheStrategyDecl {

        /**
         * 没有策略。
         */
        ITEM("a:b", null, "缺策略");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        NullStrategyDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * namespace 超长。
     */
    public enum LongNamespaceDecls implements CacheStrategyDecl {

        /**
         * 65 字符。
         */
        ITEM("n".repeat(CacheDeclTranslator.MAX_NAMESPACE_LENGTH + 1), LocalStrategy.STATIC, "超长命名空间");

        private final String namespace;
        private final LocalStrategy strategy;
        private final String description;

        LongNamespaceDecls(String namespace, LocalStrategy strategy, String description) {
            this.namespace = namespace;
            this.strategy = strategy;
            this.description = description;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public LocalStrategy getStrategy() {
            return strategy;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }
}
