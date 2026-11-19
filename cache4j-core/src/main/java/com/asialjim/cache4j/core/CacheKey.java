package com.asialjim.cache4j.core;

/**
 * 逻辑缓存 key：缓存名（namespace）+ 规范编码后的 key。
 *
 * <p><strong>本地与中央使用同一个 {@code CacheKey}</strong>，版本不进入 key：每条本地副本在 value
 * 位置携带 {@code (revision, kind)}（见 {@link LocalEntry}）。因此版本变化不会在 Redis 里产生新 key，
 * 也不需要遍历删除旧条目。</p>
 *
 * @param namespace 缓存名，例如 {@code user:detail}；规则见 {@code KeyEncoders} 文档
 * @param encoded   {@link KeyEncoder} 产出的规范字符串
 * @author Asial Jim
 */
public record CacheKey(String namespace, String encoded) {

    /**
     * namespace 最大长度。
     */
    private static final int MAX_NAMESPACE_LENGTH = 64;

    /**
     * 紧凑构造器：校验 namespace 与 encoded。
     */
    public CacheKey {
        if (namespace == null || namespace.isEmpty()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "namespace 不能为空");
        }
        if (namespace.length() > MAX_NAMESPACE_LENGTH) {
            throw new CacheException(CacheResCode.INVALID_SPEC,
                    "namespace 长度不能超过 " + MAX_NAMESPACE_LENGTH + "：" + namespace);
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-';
            if (!allowed) {
                throw new CacheException(CacheResCode.INVALID_SPEC,
                        "namespace 只允许 [A-Za-z0-9._:-]，非法字符 '" + c + "'：" + namespace);
            }
        }
        if (encoded == null || encoded.isEmpty()) {
            throw new CacheException(CacheResCode.INVALID_SPEC, "encoded key 不能为空");
        }
    }

    /**
     * 仅用于日志与排障展示；不参与存储 key 的解析。
     *
     * @return {@code namespace:encoded}
     */
    public String logical() {
        return namespace + ':' + encoded;
    }

    /**
     * 日志安全形式：key 可能含手机号、证件号等敏感信息，默认只输出哈希。
     *
     * @return {@code namespace#<hash>}
     */
    public String masked() {
        return namespace + '#' + Integer.toHexString(logical().hashCode());
    }
}
