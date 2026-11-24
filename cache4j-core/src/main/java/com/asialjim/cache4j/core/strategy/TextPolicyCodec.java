package com.asialjim.cache4j.core.strategy;

import com.asialjim.cache4j.core.CacheException;
import com.asialjim.cache4j.core.CacheResCode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 策略记录的文本编解码：逐行 {@code key=value}，人可读，适合文件 / 配置中心等文本介质。
 *
 * <p>序列化对象是 {@link CacheStrategyProperty} 的全部字段加上 4 个审计字段，因此
 * <strong>配置项只在一处定义</strong>：加一个配置项只需在 Property 上加字段，本类跟着补一行。</p>
 *
 * <p>{@code -} 表示 {@code null}；转义 {@code \\}、{@code \n}、{@code \r}。</p>
 *
 * @author Asial Jim
 */
public final class TextPolicyCodec implements PolicyCodec {

    /**
     * 当前格式版本。字段集合随 {@link CacheStrategyProperty} 扩展，读到未知 schema 直接报错。
     */
    private static final String SCHEMA = "2";

    private static final String NULL = "-";

    @Override
    public byte[] encode(StoredPolicy policy) {
        CacheStrategyProperty p = policy.property();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", SCHEMA);
        fields.put("namespace", p.getNamespace());
        fields.put("description", p.getDescription());
        fields.put("localStrategy", p.getLocalStrategy());
        fields.put("localTimeUnit", p.getLocalTimeUnit());
        fields.put("centralTimeUnit", p.getCentralTimeUnit());
        fields.put("localMaxSize", str(p.getLocalMaxSize()));
        fields.put("localTtl", str(p.getLocalTtl()));
        fields.put("localNullTtlDivisor", str(p.getLocalNullTtlDivisor()));
        fields.put("versionCheckInterval", str(p.getVersionCheckInterval()));
        fields.put("versionJitter", str(p.getVersionJitter()));
        fields.put("versionRetryBackoff", str(p.getVersionRetryBackoff()));
        fields.put("reconcileBatchSize", str(p.getReconcileBatchSize()));
        fields.put("reconcilePartitions", str(p.getReconcilePartitions()));
        fields.put("hotThresholdQps", str(p.getHotThresholdQps()));
        fields.put("coolQps", str(p.getCoolQps()));
        fields.put("hotWindow", str(p.getHotWindow()));
        fields.put("hotWindowSlices", str(p.getHotWindowSlices()));
        fields.put("maxHotKeys", str(p.getMaxHotKeys()));
        fields.put("hotSyncInterval", str(p.getHotSyncInterval()));
        fields.put("hotCooldown", str(p.getHotCooldown()));
        fields.put("coordination", p.getCoordination());
        fields.put("sketchWidthBits", str(p.getSketchWidthBits()));
        fields.put("sketchDepth", str(p.getSketchDepth()));
        fields.put("hotReportPartitions", str(p.getHotReportPartitions()));
        fields.put("hotReportBatchMax", str(p.getHotReportBatchMax()));
        fields.put("candidateCapacity", str(p.getCandidateCapacity()));
        fields.put("globalWindow", str(p.getGlobalWindow()));
        fields.put("globalHotQps", str(p.getGlobalHotQps()));
        fields.put("globalCoolQps", str(p.getGlobalCoolQps()));
        fields.put("globalDemoteWindows", str(p.getGlobalDemoteWindows()));
        fields.put("globalStaleWindows", str(p.getGlobalStaleWindows()));
        fields.put("globalClientEstimate", str(p.getGlobalClientEstimate()));
        fields.put("centralTtl", str(p.getCentralTtl()));
        fields.put("centralNullTtl", str(p.getCentralNullTtl()));
        fields.put("centralTtlJitter", p.getCentralTtlJitter() == null ? NULL : p.getCentralTtlJitter().toString());
        fields.put("failOpen", str(p.getFailOpen()));
        fields.put("allowNoExpiry", str(p.getAllowNoExpiry()));
        fields.put("configVersion", Long.toString(policy.configVersion()));
        fields.put("updatedAtMillis", Long.toString(policy.updatedAtMillis()));
        fields.put("updatedBy", policy.updatedBy());
        fields.put("reason", policy.reason());

        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=').append(escape(value)).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public StoredPolicy decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED, "策略记录为空");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int index = line.indexOf('=');
            if (index <= 0) {
                throw new CacheException(CacheResCode.CODEC_DECODE_FAILED, "策略记录行格式非法：" + line);
            }
            fields.put(line.substring(0, index), unescape(line.substring(index + 1)));
        }
        String schema = fields.get("schema");
        if (!SCHEMA.equals(schema)) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED,
                    "不支持的策略记录 schema=" + schema + "（本版本支持 " + SCHEMA + "）");
        }

        CacheStrategyProperty p = new CacheStrategyProperty();
        p.setNamespace(fields.get("namespace"));
        p.setDescription(text(fields.get("description")));
        p.setLocalStrategy(text(fields.get("localStrategy")));
        p.setLocalTimeUnit(text(fields.get("localTimeUnit")));
        p.setCentralTimeUnit(text(fields.get("centralTimeUnit")));
        p.setLocalMaxSize(longOf(fields.get("localMaxSize")));
        p.setLocalTtl(longOf(fields.get("localTtl")));
        p.setLocalNullTtlDivisor(intOf(fields.get("localNullTtlDivisor")));
        p.setVersionCheckInterval(longOf(fields.get("versionCheckInterval")));
        p.setVersionJitter(longOf(fields.get("versionJitter")));
        p.setVersionRetryBackoff(longOf(fields.get("versionRetryBackoff")));
        p.setReconcileBatchSize(intOf(fields.get("reconcileBatchSize")));
        p.setReconcilePartitions(intOf(fields.get("reconcilePartitions")));
        p.setHotThresholdQps(longOf(fields.get("hotThresholdQps")));
        p.setCoolQps(longOf(fields.get("coolQps")));
        p.setHotWindow(longOf(fields.get("hotWindow")));
        p.setHotWindowSlices(intOf(fields.get("hotWindowSlices")));
        p.setMaxHotKeys(intOf(fields.get("maxHotKeys")));
        p.setHotSyncInterval(longOf(fields.get("hotSyncInterval")));
        p.setHotCooldown(longOf(fields.get("hotCooldown")));
        p.setCoordination(text(fields.get("coordination")));
        p.setSketchWidthBits(intOf(fields.get("sketchWidthBits")));
        p.setSketchDepth(intOf(fields.get("sketchDepth")));
        p.setHotReportPartitions(intOf(fields.get("hotReportPartitions")));
        p.setHotReportBatchMax(intOf(fields.get("hotReportBatchMax")));
        p.setCandidateCapacity(intOf(fields.get("candidateCapacity")));
        p.setGlobalWindow(longOf(fields.get("globalWindow")));
        p.setGlobalHotQps(longOf(fields.get("globalHotQps")));
        p.setGlobalCoolQps(longOf(fields.get("globalCoolQps")));
        p.setGlobalDemoteWindows(intOf(fields.get("globalDemoteWindows")));
        p.setGlobalStaleWindows(intOf(fields.get("globalStaleWindows")));
        p.setGlobalClientEstimate(intOf(fields.get("globalClientEstimate")));
        p.setCentralTtl(longOf(fields.get("centralTtl")));
        p.setCentralNullTtl(longOf(fields.get("centralNullTtl")));
        p.setCentralTtlJitter(doubleOf(fields.get("centralTtlJitter")));
        p.setFailOpen(boolOf(fields.get("failOpen")));
        p.setAllowNoExpiry(boolOf(fields.get("allowNoExpiry")));

        return new StoredPolicy(p,
                longOf(fields.get("configVersion")) == null ? 1L : longOf(fields.get("configVersion")),
                longOf(fields.get("updatedAtMillis")) == null ? 0L : longOf(fields.get("updatedAtMillis")),
                text(fields.get("updatedBy")), text(fields.get("reason")));
    }

    // ------------------------------------------------------------------ 辅助

    private static String str(Long value) {
        return value == null ? NULL : Long.toString(value);
    }

    private static String str(Integer value) {
        return value == null ? NULL : Integer.toString(value);
    }

    private static String str(Boolean value) {
        return value == null ? NULL : Boolean.toString(value);
    }

    private static String text(String value) {
        return value == null || NULL.equals(value) ? null : value;
    }

    private static Long longOf(String value) {
        String decoded = text(value);
        return decoded == null ? null : Long.valueOf(decoded);
    }

    private static Integer intOf(String value) {
        String decoded = text(value);
        return decoded == null ? null : Integer.valueOf(decoded);
    }

    private static Double doubleOf(String value) {
        String decoded = text(value);
        return decoded == null ? null : Double.valueOf(decoded);
    }

    private static Boolean boolOf(String value) {
        String decoded = text(value);
        return decoded == null ? null : Boolean.valueOf(decoded);
    }

    private static String escape(String value) {
        if (value == null) {
            return NULL;
        }
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if (NULL.equals(value)) {
            return NULL;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }
}
