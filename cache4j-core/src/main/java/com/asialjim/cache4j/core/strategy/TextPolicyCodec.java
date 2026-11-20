package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;
import com.asialjim.cache4j.core.local.LocalCacheSpec;
import com.asialjim.cache4j.core.local.LocalStrategy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认编解码：<strong>逐行 {@code key=value} 文本</strong>，零依赖、可读、可 diff。
 *
 * <p>复合字段用逗号分隔的定长序列（顺序即契约），{@code -} 表示 {@code null}。
 * 值里的反斜杠与换行做转义，保证"一行一个字段"。</p>
 *
 * <p>不引 JSON 库：字段都是简单类型，手写格式省一个传递依赖；使用方已有 JSON/Protobuf 体系时
 * 实现自己的 {@link PolicyCodec} 即可。</p>
 *
 * @author Asial Jim
 */
public final class TextPolicyCodec implements PolicyCodec {

    /**
     * 格式版本：不认识的版本直接报错，不做"尽力解析"。
     */
    private static final String SCHEMA = "1";

    @Override
    public byte[] encode(StoredPolicy policy) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", SCHEMA);
        fields.put("namespace", policy.namespace());
        fields.put("description", policy.description());
        fields.put("strategy", policy.strategy() == null ? null : policy.strategy().name());
        fields.put("maxSize", policy.maxSize() == null ? null : String.valueOf(policy.maxSize()));
        fields.put("localTtl", millis(policy.localTtl()));
        fields.put("localNullTtlDivisor", policy.localNullTtlDivisor() == null
                ? null : String.valueOf(policy.localNullTtlDivisor()));
        fields.put("version", encodeVersion(policy.version()));
        fields.put("dynamic", encodeDynamic(policy.dynamic()));
        fields.put("centralTtl", millis(policy.centralTtl()));
        fields.put("centralNullTtl", millis(policy.centralNullTtl()));
        fields.put("failOpen", policy.failOpen() == null ? null : policy.failOpen().toString());
        fields.put("configVersion", String.valueOf(policy.configVersion()));
        fields.put("updatedAtMillis", String.valueOf(policy.updatedAtMillis()));
        fields.put("updatedBy", policy.updatedBy());
        fields.put("reason", policy.reason());

        StringBuilder text = new StringBuilder(256);
        fields.forEach((key, value) -> text.append(key).append('=')
                .append(escape(value)).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public StoredPolicy decode(byte[] raw) {
        Map<String, String> fields = parse(raw);
        String schema = fields.get("schema");
        if (!SCHEMA.equals(schema)) {
            throw invalid("不支持的策略编码版本：schema=" + schema);
        }
        try {
            return new StoredPolicy(
                    fields.get("namespace"),
                    nullable(fields.get("description")),
                    fields.get("strategy") == null ? null : LocalStrategy.valueOf(fields.get("strategy")),
                    fields.get("maxSize") == null ? null : Long.valueOf(fields.get("maxSize")),
                    duration(fields.get("localTtl")),
                    fields.get("localNullTtlDivisor") == null
                            ? null : Integer.valueOf(fields.get("localNullTtlDivisor")),
                    decodeVersion(fields.get("version")),
                    decodeDynamic(fields.get("dynamic")),
                    duration(fields.get("centralTtl")),
                    duration(fields.get("centralNullTtl")),
                    fields.get("failOpen") == null ? null : Boolean.valueOf(fields.get("failOpen")),
                    Long.parseLong(fields.getOrDefault("configVersion", "0")),
                    Long.parseLong(fields.getOrDefault("updatedAtMillis", "0")),
                    nullable(fields.get("updatedBy")),
                    nullable(fields.get("reason")));
        } catch (RuntimeException e) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED,
                    "策略编码解析失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 编码

    private static String encodeVersion(LocalCacheSpec.VersionSpec version) {
        if (version == null) {
            return null;
        }
        return String.join(",",
                millis(version.checkInterval()),
                millis(version.jitter()),
                millis(version.retryBackoff()),
                String.valueOf(version.reconcileBatchSize()),
                String.valueOf(version.reconcilePartitions()));
    }

    private static String encodeDynamic(LocalCacheSpec.DynamicSpec dynamic) {
        if (dynamic == null) {
            return null;
        }
        LocalCacheSpec.GlobalSpec global = dynamic.global();
        // 复合字段内部用分号，避免被外层逗号分隔展平（否则 14 段会变成 19 段）
        String globalPart = global == null ? "-" : String.join(";",
                millis(global.window()),
                String.valueOf(global.hotQps()),
                String.valueOf(global.coolQps()),
                String.valueOf(global.demoteWindows()),
                String.valueOf(global.staleWindows()),
                String.valueOf(global.clientEstimate()));
        return String.join(",",
                String.valueOf(dynamic.hotThresholdQps()),
                String.valueOf(dynamic.coolQps()),
                millis(dynamic.window()),
                String.valueOf(dynamic.windowSlices()),
                String.valueOf(dynamic.maxHotKeys()),
                millis(dynamic.syncInterval()),
                millis(dynamic.cooldown()),
                dynamic.coordination().name(),
                globalPart,
                String.valueOf(dynamic.sketchWidthBits()),
                String.valueOf(dynamic.sketchDepth()),
                String.valueOf(dynamic.hotReportPartitions()),
                String.valueOf(dynamic.hotReportBatchMax()),
                String.valueOf(dynamic.candidateCapacity()));
    }

    private static String millis(Duration duration) {
        return duration == null ? null : String.valueOf(duration.toMillis());
    }

    // ------------------------------------------------------------------ 解码

    private static LocalCacheSpec.VersionSpec decodeVersion(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        return new LocalCacheSpec.VersionSpec(
                Duration.ofMillis(Long.parseLong(parts[0])),
                Duration.ofMillis(Long.parseLong(parts[1])),
                Duration.ofMillis(Long.parseLong(parts[2])),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]));
    }

    private static LocalCacheSpec.DynamicSpec decodeDynamic(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        LocalCacheSpec.GlobalSpec global = "-".equals(parts[8]) ? null : decodeGlobal(parts[8]);
        return new LocalCacheSpec.DynamicSpec(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Duration.ofMillis(Long.parseLong(parts[2])),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]),
                Duration.ofMillis(Long.parseLong(parts[5])),
                Duration.ofMillis(Long.parseLong(parts[6])),
                LocalCacheSpec.Coordination.valueOf(parts[7]),
                global,
                Integer.parseInt(parts[9]),
                Integer.parseInt(parts[10]),
                Integer.parseInt(parts[11]),
                Integer.parseInt(parts[12]),
                Integer.parseInt(parts[13]));
    }

    private static LocalCacheSpec.GlobalSpec decodeGlobal(String raw) {
        String[] parts = raw.split(";", -1);
        return new LocalCacheSpec.GlobalSpec(
                Duration.ofMillis(Long.parseLong(parts[0])),
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2]),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]),
                Integer.parseInt(parts[5]));
    }

    private static Duration duration(String raw) {
        return raw == null ? null : Duration.ofMillis(Long.parseLong(raw));
    }

    // ------------------------------------------------------------------ 工具

    private static Map<String, String> parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw invalid("策略编码为空");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int index = line.indexOf('=');
            if (index <= 0) {
                throw invalid("策略编码格式错误（缺少 key=value）：" + line);
            }
            fields.put(line.substring(0, index), unescape(line.substring(index + 1)));
        }
        return fields;
    }

    private static String escape(String value) {
        if (value == null) {
            return "-";
        }
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if ("-".equals(value)) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static CacheException invalid(String message) {
        return new CacheException(CacheResCode.CODEC_DECODE_FAILED, message);
    }
}
