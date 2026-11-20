package com.asialjim.cache4j.core;

import com.asialjim.cache4j.core.log.CacheLogger;

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

    private static final CacheLogger LOG = CacheLogger.get(KeyEncoders.class);

    private static final String HASH_PREFIX = "H43:";
    private static final String SEGMENT_HASH_PREFIX = "#43:";

    private KeyEncoders() {
    }

    /**
     * 默认规范编码器。
     *
     * @return 编码器
     */
    public static KeyEncoder canonical() {
        return CanonicalEncoder.INSTANCE;
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
     * 带类型后缀的编码器：值与 {@link #plain()} 完全一致，但在<strong>每个编码单元的末尾</strong>
     * 追加 {@code :<type><length>}。
     *
     * <p>与 {@link #canonical()} 的区别是<strong>元信息的位置</strong>：canonical 把
     * {@code <type><length>:} 放在值前面，于是 Redis key 变成
     * {@code _c4j:{user:detail}:s2:42}——命名空间与值之间夹了一层看不懂的东西，层级结构被打断。
     * 本编码器把它挪到末尾：</p>
     *
     * <pre>
     * String "keep"   → keep:s4        （类型 s，4 字节）
     * Integer 42      → 42:i2          （类型 i，2 字节）
     * 多参数 (a, 1)   → a:s1:1:i1      （逐段各自带后缀）
     * </pre>
     *
     * <p>Redis 里仍然能按命名空间分层查看：{@code _c4j:{user:detail}:} 之后紧跟值本身。</p>
     *
     * <p>取舍：保留类型区分（{@code "42"} 与 {@code 42} 不撞 key），但 key 比 {@link #plain()}
     * 长一截；多参数场景下后缀会更多。默认不开启，按需选用：</p>
     *
     * <pre>
     * Cache4jSpringOptions.builder().keyEncoder(KeyEncoders.tagged())
     * </pre>
     *
     * @return 编码器
     */
    public static KeyEncoder tagged() {
        return TaggedEncoder.INSTANCE;
    }

    /**
     * 原样编码器：只接受 {@link String}，其余类型报错。
     *
     * <p>用于"key 已经是字符串"的适配场景（例如 Solon 的 {@code CacheService}）。</p>
     *
     * @return 编码器
     */
    public static KeyEncoder identity() {
        return key -> {
            if (key instanceof String s) {
                return s;
            }
            LOG.error(CacheLogger.Subject.KEY_ENCODE, "编码", "identity 编码器只接受 String，实际为 " + typeName(key));
            throw new CacheException(CacheResCode.KEY_ENCODE_FAILED,
                    "identity 编码器只接受 String，实际为 " + typeName(key));
        };
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
    private enum TaggedEncoder implements KeyEncoder {

        INSTANCE;

        @Override
        public String encode(Object key) {
            StringBuilder builder = new StringBuilder();
            append(key, builder);
            return builder.toString();
        }

        private static void append(Object value, StringBuilder builder) {
            if (value instanceof Object[] array) {
                appendAll(java.util.Arrays.asList(array), builder);
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                appendAll(iterable, builder);
                return;
            }
            String plain = value == null ? "null" : String.valueOf(value);
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(plain).append(':').append(typeTag(value)).append(utf8Length(plain));
        }

        private static void appendAll(Iterable<?> values, StringBuilder builder) {
            for (Object each : values) {
                append(each, builder);
            }
        }

        /**
         * @param value 值
         * @return 单字符类型标签（与 {@link #canonical()} 的类型表一致）
         */
        private static String typeTag(Object value) {
            if (value == null) {
                return "n";
            }
            if (value instanceof CharSequence) {
                return "s";
            }
            if (value instanceof Integer) {
                return "i";
            }
            if (value instanceof Long) {
                return "l";
            }
            if (value instanceof Short) {
                return "h";
            }
            if (value instanceof Byte) {
                return "y";
            }
            if (value instanceof Boolean) {
                return "B";
            }
            if (value instanceof Character) {
                return "C";
            }
            if (value instanceof Float) {
                return "f";
            }
            if (value instanceof Double) {
                return "d";
            }
            if (value instanceof java.math.BigDecimal) {
                return "D";
            }
            if (value instanceof java.util.UUID) {
                return "u";
            }
            if (value instanceof java.time.Instant) {
                return "t";
            }
            if (value instanceof java.time.LocalDate) {
                return "L";
            }
            if (value instanceof java.time.LocalDateTime) {
                return "T";
            }
            if (value instanceof java.time.OffsetDateTime) {
                return "O";
            }
            if (value instanceof java.time.ZonedDateTime) {
                return "Z";
            }
            if (value instanceof Enum<?>) {
                return "e";
            }
            return "o";
        }
    }

    /**
     * 规范编码器实现。
     */
    private enum CanonicalEncoder implements KeyEncoder {
        INSTANCE;

        @Override
        public String encode(Object key) {
            String encoded = segment(key);
            return encoded.length() > MAX_KEY_BYTES ? HASH_PREFIX + sha256Base64Url(encoded) : encoded;
        }

        /**
         * 编码单个值并做段级长度保护。
         */
        private String segment(Object value) {
            String raw = rawSegment(value);
            return utf8Length(raw) > MAX_SEGMENT_BYTES ? SEGMENT_HASH_PREFIX + sha256Base64Url(raw) : raw;
        }

        private String rawSegment(Object value) {
            if (value == null) {
                return "n;";
            }
            if (value instanceof String s) {
                return "s" + utf8Length(s) + ':' + s;
            }
            if (value instanceof Integer v) {
                return sized("i", Integer.toString(v));
            }
            if (value instanceof Long v) {
                return sized("l", Long.toString(v));
            }
            if (value instanceof Short v) {
                return sized("h", Short.toString(v));
            }
            if (value instanceof Byte v) {
                return sized("y", Byte.toString(v));
            }
            if (value instanceof Boolean v) {
                return v ? "B1:1" : "B1:0";
            }
            if (value instanceof Character v) {
                return sized("C", String.valueOf(v));
            }
            if (value instanceof Float v) {
                return sized("f", Float.toString(v));
            }
            if (value instanceof Double v) {
                return sized("d", Double.toString(v));
            }
            if (value instanceof BigDecimal v) {
                return sized("D", plain(v));
            }
            if (value instanceof UUID v) {
                return sized("u", v.toString());
            }
            if (value instanceof Instant v) {
                return sized("t", v.toString());
            }
            if (value instanceof LocalDate v) {
                return sized("L", v.toString());
            }
            if (value instanceof LocalDateTime v) {
                return sized("T", v.toString());
            }
            if (value instanceof OffsetDateTime v) {
                return sized("O", v.toString());
            }
            if (value instanceof ZonedDateTime v) {
                return sized("Z", v.toString());
            }
            if (value instanceof Enum<?> v) {
                return sized("e", v.getDeclaringClass().getName() + '#' + v.name());
            }
            if (value instanceof byte[] v) {
                return sized("x", Base64.getUrlEncoder().withoutPadding().encodeToString(v));
            }
            if (value instanceof Optional<?> v) {
                return v.isEmpty() ? "p0;" : "p1[" + segment(v.get()) + ']';
            }
            if (value instanceof KeyWritable v) {
                CanonicalSink sink = new CanonicalSink();
                v.writeKey(sink);
                return "k" + sink.finish();
            }
            if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                List<String> parts = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    parts.add(segment(java.lang.reflect.Array.get(value, i)));
                }
                return "a" + length + '[' + String.join(",", parts) + ']';
            }
            if (value instanceof Set<?> set) {
                List<String> parts = new ArrayList<>(set.size());
                for (Object element : set) {
                    parts.add(segment(element));
                }
                parts.sort(String::compareTo);
                return "c" + parts.size() + '[' + String.join(",", parts) + ']';
            }
            if (value instanceof Collection<?> collection) {
                List<String> parts = new ArrayList<>(collection.size());
                for (Object element : collection) {
                    parts.add(segment(element));
                }
                return "c" + parts.size() + '[' + String.join(",", parts) + ']';
            }
            if (value instanceof Map<?, ?> map) {
                List<String[]> pairs = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    pairs.add(new String[]{segment(entry.getKey()), segment(entry.getValue())});
                }
                pairs.sort((a, b) -> a[0].compareTo(b[0]));
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < pairs.size(); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(pairs.get(i)[0]).append('=').append(pairs.get(i)[1]);
                }
                return "m" + pairs.size() + '{' + builder + '}';
            }
            LOG.error(CacheLogger.Subject.KEY_ENCODE, "编码", "不支持的 key 类型：" + typeName(value)
                    + "，请实现 KeyWritable 或提供自定义 KeyEncoder");
            throw new CacheException(CacheResCode.KEY_ENCODE_FAILED,
                    "不支持的 key 类型：" + typeName(value) + "，请实现 KeyWritable 或提供自定义 KeyEncoder");
        }

        private String sized(String tag, String payload) {
            return tag + utf8Length(payload) + ':' + payload;
        }

        private String plain(BigDecimal value) {
            BigDecimal normalized = value.stripTrailingZeros();
            if (normalized.signum() == 0) {
                return "0";
            }
            String plain = normalized.toPlainString();
            require(!plain.contains("E") && !plain.contains("e"),
                    "BigDecimal 不允许科学计数法：" + value);
            return plain;
        }
    }

    /**
     * 供 {@link KeyWritable} 使用的规范片段写入器。
     */
    private static final class CanonicalSink implements KeySink {

        private final StringBuilder builder = new StringBuilder();
        private int depth;

        @Override
        public KeySink text(String value) {
            builder.append('s').append(utf8Length(value)).append(':').append(value);
            return this;
        }

        @Override
        public KeySink number(long value) {
            String payload = Long.toString(value);
            builder.append('l').append(payload.length()).append(':').append(payload);
            return this;
        }

        @Override
        public KeySink bool(boolean value) {
            builder.append(value ? "B1:1" : "B1:0");
            return this;
        }

        @Override
        public KeySink beginArray(int size) {
            builder.append('a').append(size).append('[');
            depth++;
            return this;
        }

        @Override
        public KeySink endArray() {
            require(depth > 0, "endArray 调用次数多于 beginArray");
            depth--;
            builder.append(']');
            return this;
        }

        private String finish() {
            require(depth == 0, "beginArray 未配对结束");
            return builder.toString();
        }
    }

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
