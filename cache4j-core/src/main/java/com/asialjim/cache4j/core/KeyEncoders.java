package com.asialjim.cache4j.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 内置 key 编码器工厂。
 *
 * <h2>规范编码（{@link #canonical()}）</h2>
 *
 * <p>编码单元为 {@code <type><length>:<payload>}：{@code type} 是单字符类型标签，
 * {@code length} 是 payload 的 UTF-8 字节长度（复合类型是元素/条目个数），
 * 所有分隔符都在自定界 payload 之外，因此不会与内容冲突。</p>
 *
 * <table border="1">
 *   <caption>类型标签</caption>
 *   <tr><th>标签</th><th>类型</th><th>示例</th></tr>
 *   <tr><td>n</td><td>null</td><td>{@code n;}</td></tr>
 *   <tr><td>s</td><td>String</td><td>{@code s2:42}</td></tr>
 *   <tr><td>i / l / h / y</td><td>Integer / Long / Short / Byte</td><td>{@code l2:42}</td></tr>
 *   <tr><td>B</td><td>Boolean</td><td>{@code B1:1}</td></tr>
 *   <tr><td>C</td><td>Character</td><td>{@code C1:a}</td></tr>
 *   <tr><td>f / d</td><td>Float / Double</td><td>{@code d3:1.5}</td></tr>
 *   <tr><td>D</td><td>BigDecimal（去尾零、禁科学计数）</td><td>{@code D1:0}</td></tr>
 *   <tr><td>u</td><td>UUID</td><td>{@code u36:...}</td></tr>
 *   <tr><td>t / L / T / O / Z</td><td>时间类型</td><td>{@code L10:2026-11-19}</td></tr>
 *   <tr><td>e</td><td>Enum（含 FQCN）</td><td>{@code e...}</td></tr>
 *   <tr><td>x</td><td>byte[]（Base64 URL 无填充）</td><td>{@code x4:YWJj}</td></tr>
 *   <tr><td>p</td><td>Optional</td><td>{@code p1[s1:a]}</td></tr>
 *   <tr><td>a</td><td>数组</td><td>{@code a2[i1:1,i1:2]}</td></tr>
 *   <tr><td>c</td><td>Collection</td><td>{@code c2[s1:a,i1:1]}</td></tr>
 *   <tr><td>m</td><td>Map（按编码后的 key 排序）</td><td>{@code m1[s1:k=i1:1]}</td></tr>
 *   <tr><td>k</td><td>KeyWritable</td><td>{@code k...}</td></tr>
 *   <tr><td>#</td><td>段哈希（单段超过 64 字节）</td><td>{@code #43:...}</td></tr>
 *   <tr><td>H</td><td>整键哈希（编码超过 512 字节）</td><td>{@code H43:...}</td></tr>
 * </table>
 *
 * <p>不支持的类型直接抛 {@link CacheResCode#KEY_ENCODE_FAILED}，<strong>绝不</strong>回退到
 * {@code Object.toString()}。</p>
 *
 * @author Asial Jim
 */
public final class KeyEncoders {

    /**
     * 单段最大 UTF-8 字节数，超出则对整段做哈希。
     */
    private static final int MAX_SEGMENT_BYTES = 64;

    /**
     * 编码结果最大长度，超出则对整键做哈希。
     */
    private static final int MAX_KEY_BYTES = 512;

    private static final Logger LOG = LoggerFactory.getLogger(KeyEncoders.class);

    private static final String HASH_PREFIX = "H43:";
    private static final String SEGMENT_HASH_PREFIX = "#43:";

    private KeyEncoders() {
    }

    /**
     * 原生风格编码器：{@code key} 直接就是可读值，<strong>不加类型标签、不加长度前缀</strong>。
     *
     * <p>这是默认编码器。产出的 key 与 Spring Data Redis 原生写法一致：</p>
     *
     * <pre>
     * String "keep"      → keep
     * Integer 42         → 42
     * 多参数 (a, 1)      → a:1
     * </pre>
     *
     * <p>于是 Redis 里的 key 是可分层查看的：</p>
     *
     * <pre>
     * _c4j:{user:detail}:42
     * _c4j:{user:detail}:_jim
     * </pre>
     *
     * <p><strong>代价</strong>（与 Spring 原生一致，属于使用约束）：</p>
     * <ul>
     *   <li>不加类型标签 ⇒ {@code "42"}(String) 与 {@code 42}(Integer) 编码结果相同。同一个缓存里
     *       不要混用同值的不同类型 key；</li>
     *   <li>不加长度前缀 ⇒ 多参数拼接在"参数本身含 {@code :}"时可能歧义
     *       （{@code (a, b:c)} 与 {@code (a:b, c)} 都是 {@code a:b:c}）。key 里避免出现 {@code :} 即可。</li>
     * </ul>
     *
     * <p>需要严格单射时改用 {@link #canonical()}。</p>
     *
     * @return 编码器
     */
    public static KeyEncoder plain() {
        return PlainEncoder.INSTANCE;
    }

    /**
     * 委托给业务函数，由业务自行保证确定性与单射。
     *
     * @param function 编码函数
     * @return 编码器
     */
    public static KeyEncoder delegate(Function<Object, String> function) {
        Objects.requireNonNull(function, "function");
        return function::apply;
    }

    private static String typeName(Object key) {
        return key == null ? "null" : key.getClass().getName();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CacheException(CacheResCode.KEY_ENCODE_FAILED, message);
        }
    }

    /**
     * 原生风格编码器实现：值原样、参数用 {@code :} 连接。
     */
    private enum PlainEncoder implements KeyEncoder {

        INSTANCE;

        @Override
        public String encode(Object key) {
            StringBuilder builder = new StringBuilder();
            append(key, builder);
            return builder.toString();
        }

        private static void append(Object value, StringBuilder builder) {
            if (value == null) {
                builder.append("null");
                return;
            }
            if (value instanceof CharSequence text) {
                builder.append(text);
                return;
            }
            if (value instanceof Object[] array) {
                appendAll(java.util.Arrays.asList(array), builder);
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                appendAll(iterable, builder);
                return;
            }
            builder.append(value);
        }

        private static void appendAll(Iterable<?> values, StringBuilder builder) {
            boolean first = true;
            for (Object each : values) {
                if (!first) {
                    builder.append(':');
                }
                append(each, builder);
                first = false;
            }
        }
    }

    /**
     * 带类型后缀的编码器实现：值原样、后缀在末尾。
     */
    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new CacheException(CacheResCode.LOCAL_INIT_FAILED, "缺少 SHA-256 实现", e);
        }
    }
}
