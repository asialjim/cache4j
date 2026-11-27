package com.asialjim.cache4j.central;

import com.asialjim.cache4j.Cache4jKey;

/**
 * 中央缓存的 key 布局：<strong>数据</strong>与<strong>版本</strong>。
 *
 * <p>只产出字符串，不产出、也不接触原始字节——"这个字符串怎么落到存储里"
 * 是存储层的事。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * 数据 key        C4J-L2:{namespace}:#{hash}:{key}                    field = {key}
 * 版本 HASH key   C4J-L2:{namespace}:#{hash}:__rev                    field = {key}
 * 热度 HASH 前缀  C4J-L2:{namespace}:#{hash}:__hotkey:      ← 本类定义到这里
 * 热度 HASH key   C4J-L2:{namespace}:#{hash}:__hotkey:{window}         field = {key}
 *                                                     ↑ 这一段由存储层自己算
 * </pre>
 *
 * <p>{@code #{hash}} 是 CRC16 取模得到的 hash tag，让同一 namespace 的数据 key 与
 * 版本 key 落进同一个集群槽位——对账要在一次 {@code HMGET} 里读多个版本 field，
 * 跨槽在 Redis Cluster 下会被直接拒绝。</p>
 *
 * <p>版本 HASH 的形状是"一个 namespace 一个 HASH、field 就是 key"，这带来两个性质：
 * 对账时缺 field 天然表示"已删除"（不需要 tombstone），{@code HLEN} 恒等于存活 key 数。</p>
 *
 * <h2>哪些部分是抽象层定义的</h2>
 * <p><strong>全部前缀与分隔符</strong>——包括 {@code __rev} 与 {@code __hotkey:} 这两段。
 * 理由很直接：namespace 下的 key 空间是一份<strong>共享命名约定</strong>。
 * 若把 {@code __hotkey:} 前缀交给各存储实现自己写，同一个 namespace 用两套存储、
 * 或换一次存储实现，key 空间就散了；而这段前缀本身没有任何实现相关的东西，
 * 没有理由放任它漂移。</p>
 *
 * <table border="1">
 *   <caption>切割线</caption>
 *   <tr><th>本类定义（协议）</th><th>存储层决定（实现）</th></tr>
 *   <tr><td>数据 / 版本 / 热度 的 key 前缀</td>
 *       <td>热度 key 末尾的<strong>窗口段</strong>怎么写</td></tr>
 *   <tr><td>hash tag（同 namespace 同槽）</td>
 *       <td>窗口用时间戳、序号还是别的</td></tr>
 *   <tr><td>field = 业务 key 的规则</td>
 *       <td>热度 HASH 的 TTL 多长</td></tr>
 * </table>
 *
 * <p><strong>窗口段为什么必须归存储层</strong>：窗口只能由<strong>中央时钟</strong>划分——
 * 本地时钟不可信（装完系统没同步 NTP、容器长期漂移、云主机迁移跳变；时区不是问题，
 * {@code currentTimeMillis} 是 UTC epoch、跨时区取值相同），而唯一能拿到中央时钟的地方
 * 就是存储端。所以本类只给到 {@code __hotkey:} 为止，存储层算完窗口自己拼上去。</p>
 *
 * <h2>不自己实现 CRC16</h2>
 * <p>数据 key 一律委托 {@link Cache4jKey#centralKey()}，保证与 core 里已有的布局永远一致。
 * 自己抄一份哈希表的代价是——哪天 core 改了算法，这里会静默地对不上，
 * 症状要到"跨槽被拒"或"版本查不到"才暴露。</p>
 */
public final class CentralKeyLayout4j {

    /**
     * 无状态单例。
     */
    public static final CentralKeyLayout4j INSTANCE = new CentralKeyLayout4j();

    /**
     * 版本 HASH 在 key 布局里占用的最后一段。
     */
    public static final String REVISION_SUFFIX = "__rev";

    /**
     * 热度 HASH 在 key 布局里占用的段前缀。
     *
     * <p>它后面跟着的<strong>窗口段由存储层自己算</strong>——见
     * {@link #hotKeyPrefix(String)}。</p>
     */
    public static final String HOTKEY_SUFFIX = "__hotkey";


    private CentralKeyLayout4j() {
    }

    /**
     * 数据 key。
     *
     * @param namespace 命名空间
     * @param key       已编码的业务 key
     * @return {@code C4J-L2:{namespace}:#{hash}:{key}}
     */
    public String dataKey(String namespace, String key) {
        return new Cache4jKey(namespace, key, null).centralKey();
    }

    /**
     * 数据 key。
     *
     * @param key 缓存键
     * @return 数据 key
     */
    public String dataKey(Cache4jKey key) {
        return dataKey(key.namespace(), key.key());
    }

    /**
     * 数据 key 的<strong>前缀</strong>，以冒号结尾。
     *
     * <p>主要用途是清空 namespace 时的 {@code SCAN MATCH} 模板：
     * {@code SCAN MATCH {dataKeyPrefix}*} 会命中本 namespace 下的
     * <strong>全部三类 key</strong>（数据 / {@code __rev} / {@code __hotkey:*}），
     * 正是"一把全删"需要的范围。</p>
     *
     * <p>不用 {@code HKEYS __rev} 反查存活 key：版本 field 只在写入时添加，
     * 数据 TTL 到期不会自动 {@code HDEL}，会返回幽灵 key；而写入未走原子 Lua 时又会漏。
     * 两个方向都失真。</p>
     *
     * <p>实现上就是"key 段为空的数据 key"，因此前缀必然与 {@link #dataKey} 同源，
     * 不会出现两套拼接逻辑各写各的。</p>
     *
     * @param namespace 命名空间
     * @return {@code C4J-L2:{namespace}:#{hash}:}，末尾带冒号
     */
    public String dataKeyPrefix(String namespace) {
        return dataKey(namespace, "");
    }

    /**
     * 版本 HASH 的 key（每个 namespace 一个）。
     *
     * <p>复用数据 key 的布局、只把最后一段换成 {@link #REVISION_SUFFIX}，
     * 因此两者必然落在同一个 hash tag 下。</p>
     *
     * @param namespace 命名空间
     * @return {@code C4J-L2:{namespace}:#{hash}:__rev}
     */
    public String revisionKey(String namespace) {
        return dataKey(namespace, REVISION_SUFFIX);
    }

    /**
     * 版本 HASH 里的 field 名。
     *
     * <p>直接用业务 key 本身：到这一步 key 已经过 {@code KeyEncoder} 处理，
     * 是可安全放进 HASH field 的字符串，不需要二次编码。</p>
     *
     * @param key 业务 key
     * @return field 名
     */
    public String revisionField(String key) {
        return key;
    }

    /**
     * 版本 HASH 里的 field 名。
     *
     * @param key 缓存键
     * @return field 名
     */
    public String revisionField(Cache4jKey key) {
        return revisionField(key.key());
    }


    // ------------------------------------------------------------------ 热度

    /**
     * 热度 HASH 的 key <strong>前缀</strong>，以冒号结尾。
     *
     * <p>返回形如 {@code C4J-L2:{namespace}:#{hash}:__hotkey:} 的字符串，
     * 存储层算完窗口后直接拼上即可：</p>
     *
     * <pre>{@code
     * // 存储层（Lua 里）
     * local windowStart = math.floor(nowSeconds / windowSeconds) * windowSeconds
     * local hotKey = prefix .. windowStart        -- prefix 即本方法的返回值
     * }</pre>
     *
     * <p>窗口段<strong>刻意不由本类生成</strong>：窗口必须用中央时钟划分
     * （Redis {@code TIME}），而本地时钟不可信——装完系统没同步 NTP、容器长期漂移、
     * 云主机迁移跳变都会让节点算错窗口（时区不是问题：{@code currentTimeMillis}
     * 是 UTC epoch，跨时区取值相同）。只有存储端拿得到中央时钟。</p>
     *
     * @param namespace 命名空间
     * @return {@code C4J-L2:{namespace}:#{hash}:__hotkey:}，末尾带冒号
     */
    public String hotKeyPrefix(String namespace) {
        return dataKey(namespace, HOTKEY_SUFFIX) + ":";
    }

    /**
     * 热度 HASH 里的 field 名。
     *
     * <p>与 {@link #revisionField} 同一口径：直接用业务 key 本身。</p>
     *
     * @param key 业务 key
     * @return field 名
     */
    public String hotKeyField(String key) {
        return key;
    }

    /**
     * 热度 HASH 里的 field 名。
     *
     * @param key 缓存键
     * @return field 名
     */
    public String hotKeyField(Cache4jKey key) {
        return hotKeyField(key.key());
    }
}
