# 中央缓存设计（`cache4j-central` / `cache4j-central-redis`）

> 本文是 [总体设计](DESIGN.md) 的展开，只讨论中央缓存（L2）。
>
> **三层边界**：
>
> 1. `cache4j-central` 定义**中央缓存 SPI**：读写、TTL、负缓存、provider，不认识 Redis；
> 2. `cache4j-central-redis` 定义**Redis 缓存 SPI**：`RedisCommands` 命令契约、key 布局、envelope、`RedisCentralCache`；**不依赖任何 Redis 客户端**（Jedis / Lettuce / Redisson），也不接入 Spring；
> 3. [`cache4j-spring-redis`](ADAPTERS.md) 负责**接入 Spring Data Redis**：用 `RedisConnectionFactory` 实现 `RedisCommands`；底层使用 Jedis、Lettuce 还是其他客户端，由用户在 Spring 侧选择。

---

## 1. 中央缓存 SPI（通用契约在 `cache4j-core`，中央侧在 `cache4j-central`）

`Cache`、`Lookup`、`CacheKey`、`CacheCodec` 是 core 的通用契约；`CentralCache`、`CentralCacheSpec`、`CentralCacheProvider` 是中央侧 SPI（接口由 core 暴露），由 `cache4j-central` 负责实现与装配。中央侧 SPI 不认识 Redis，也不认识任何 Redis 客户端。

### 1.1 契约

```java
public interface CentralCache<V> extends Cache<CacheKey, V> {

    /** 批量读；返回顺序与入参一致；单飞/降级由编排层处理 */
    Map<CacheKey, Lookup<V>> lookupAll(Collection<CacheKey> keys);

    /**
     * 一次合并 I/O 返回**该 key** 的版本与值。
     * Redis 实现：`HGET __rev field` + `GET data`（同一 hash tag，一个 Lua / pipeline 合成一次往返）。
     */
    VersionedLookup<V> lookupWithRevision(CacheKey key);

    /** 批量版本化读；每项自带该 key 的 revision */
    Map<CacheKey, VersionedLookup<V>> lookupAllWithRevision(Collection<CacheKey> keys);

    /** 指定 key 的当前版本；用于诊断与测试，读路径优先用合并读 */
    long revision(CacheKey key);

    /** 写值并原子更新该 key 的版本，返回新版本 */
    long putAndGetRevision(CacheKey key, V value, Duration ttl);

    /** 原子写负缓存并更新该 key 的版本，返回新版本 */
    long putNegativeAndGetRevision(CacheKey key, Duration ttl);

    /** 成功写入返回新版本；已存在返回 empty，不更新版本 */
    OptionalLong putIfAbsentAndGetRevision(CacheKey key, V value, Duration ttl);

    /** 删除数据并删掉该 key 的版本 field（删除对其他节点可见：对账时“中央缺字段”） */
    @Override
    void evict(CacheKey key);

    /**
     * 批量取版本（对账用）：只取版本、不取数据值。
     * 返回 map 中缺失的 key 表示中央已不再持有它（删除 / 清空 / 数据已按 TTL 过期）。
     * 存储实现应在读取时顺手清理自己的过期元数据（如 Redis 的 __rev 死 field），对编排层透明。
     */
    Map<CacheKey, Long> revisions(Collection<CacheKey> keys);

    /** 轻量探活；不抛异常，失败返回 false */
    boolean ping();

    CentralCacheSpec spec();
}
```

```java
public record VersionedLookup<V>(long revision, Lookup<V> lookup) {}

/** 版本 field 的值：revision + 该值的过期时刻（0 = 永不过期） */
public record RevAndExpire(long revision, long expireAtMillis) {}
```

约定：

- `CentralCache` 继承的 `put` / `putIfAbsent` / `evict` / `clear` **必须**复用带版本的方法（`putAndGetRevision` 等）并在内部丢弃返回值；不存在"只写值、不更新版本"的中央写路径。
- 中央缓存实现**不吞异常**：连接失败、命令失败、超时都抛 `CacheException`（`CENTRAL_UNAVAILABLE` / `CENTRAL_TIMEOUT`）。`failOpen` 是编排层（`cache4j-core` 的 `TwoLevelCache`）的策略，不是存储层策略。
- `CentralCacheSpec.failOpen` 只是"该缓存名的默认降级策略"，由编排层读取；单独使用 `CentralCache` 时始终按严格模式处理。
- **版本更新必须与写操作原子完成**：Redis 用 Lua 把 `SET data` 与 `HSET __rev field` 放在一个脚本里；内存实现用锁。
- 写操作返回的新版本是本地更新的依据；实现不得返回旧版本，也不得在写失败时更新版本。
- `putIfAbsent` 未写入时不更新版本；写入成功才更新。
- `clear()` 是两组动作：`DEL __rev`（整张版本表）+ `SCAN + UNLINK` 数据 key；两者不需要保持原子。版本表先删最安全：此后任何访问都会因"版本不存在"而回中央。
- `evict` 不保留版本（`DEL data` + `HDEL field`），因此 `HLEN` 恒等于"存活 key 数 + TTL 已过期但还未被对账清掉的少量滞后"；不需要 `HRANDFIELD` 抽样，也不需要整表 TTL（整表过期会造成一次集中回源尖峰）。
- `close` 只关闭该视图持有的资源；provider 负责连接生命周期，多个 `CentralCache` 共享同一个 provider 时不能互相关闭底层客户端。
- `cache4j-central` 的 `pom.xml` 只有 `cache4j-core` 与 `standard4j`；不得出现 Redis、Spring、Solon 或任何客户端依赖。

```java
public record CentralCacheSpec(
        String namespace,
        Duration ttl,             // 默认 TTL，必须为正
        Duration nullTtl,         // 负缓存 TTL；0 表示关闭
        boolean failOpen,         // 编排层默认降级策略
        double ttlJitter,         // 0–0.5，默认 0.1
        boolean allowNoExpiry     // 默认 false；true 才允许无 TTL
) {}
```

### 1.2 版本号的语义

- 版本号是**每个 key 一个**，取值是"该 key 最后一次写入时的中央时钟时间戳（毫秒）"，只要求"每次写产生不同的值"；
- 写入时用 Lua 取 `newRev = max(now, cur + 1)`：避免同一毫秒内两次写拿到相同值（相等会让其他节点的失配检测失效）；
- 版本号**不要求跨节点单调**：不同节点时钟可能相差数秒，本地判断只看"不相等"（`entry.revision != centralRevision → 失效并回读`），方向无关；
- 因此不需要 epoch、不需要随机前缀、不需要分布式时钟——`__rev` 丢失后重建的版本号天然是更新的时间，不会回到 0；
- 版本号不是缓存值的一部分，不参与 envelope；它存在该 namespace 的 `__rev` HASH 里（field = `encodedKey`，value = `<revision>|<expireAtMillis>`）；
- `expireAtMillis` 是该**数据 key** 的过期时刻（写时 TTL 已知，`0` 表示永不过期）：它的唯一用途是让对账能识别“数据已过期、field 还留着”的死版本并 `HDEL`，所以不需要额外的清理策略。
- 读到比本地更小的版本也不会使旧值复活：本地只会用新值覆盖这条副本；仍建议记 `versionRegression` 指标用于观测时钟异常；
- `evict` **不保留版本**：同一个 Lua 内 `DEL data` + `HDEL field`。删除对其他节点可见，因为对账时会发现“本地有、中央缺字段”。

> 版本号只需要"单调且不重复"，不需要全局有序。不同缓存名的版本互不影响。

### 1.3 `CentralCacheProvider` 与热 key 协调

```java
public interface CentralCacheProvider extends AutoCloseable {

    <V> CentralCache<V> create(CentralCacheSpec spec, CacheCodec<V> codec);

    /** 可选：提供跨节点热 key 协调；没有则 DYNAMIC + GLOBAL 启动失败 */
    default Optional<HotKeyCoordinator> hotKeyCoordinator() {
        return Optional.empty();
    }

    @Override
    void close();
}
```

`HotKeyCoordinator` 是 `cache4j-core` 定义的 SPI，与缓存存储解耦：

```java
public interface HotKeyCoordinator extends AutoCloseable {

    /** 一次 I/O：上报增量 + 查询全局窗口 + 返回状态 */
    HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta);

    /** 批量版本；顺序与入参一致 */
    Map<CacheKey, HotKeyReport> reportAndStatusAll(
            String namespace, Map<CacheKey, Long> deltas);

    @Override
    void close();
}

public record HotKeyReport(
        HotKeyStatus status,     // HOT / ACTIVE / COOLING / UNKNOWN
        long windowCount,        // 当前 + 上一窗口合计
        long lastActiveWindow,
        long lastHotWindow
) {}
```

---

## 2. Redis 值格式（envelope，`cache4j-central-redis`）

> envelope 属于 Redis 缓存 SPI，由 `cache4j-central-redis` 定义；`cache4j-central` 只约定"值是一个可编解码的字节序列"，不关心封装格式。

Redis 里的 value 不是裸的业务字节，而是带元信息的 envelope：

```text
+--------+---------+--------+----------+-------------------+
| magic  | version | flags  | reserved | payload           |
| 1 byte | 1 byte  | 1 byte | 1 byte   | N bytes           |
+--------+---------+--------+----------+-------------------+
  0xC4     0x01      bit0=负缓存            codec 输出
```

- `magic`：0xC4，快速识别"这是 cache4j 的数据"，避免误读其他系统写入的 key；
- `version`：当前 0x01；未来可加压缩、加密、类型信息；
- `flags`：bit0 = 负缓存（payload 为空）；其余位保留；
- `reserved`：0；
- `payload`：`CacheCodec.encode(value)`；负缓存时为空。

为什么需要 envelope：

1. **区分"缓存了 null"与"没有缓存"**：Redis 的 `GET` 对两者都返回 nil，必须靠 value 内容区分；
2. **可演进**：版本号、压缩位、未来字段都有位置；
3. **可诊断**：看到 magic 就知道数据来源，减少"这个 key 是谁写的"的排查成本。

约束：

- 解码时校验长度、magic、version；任一不合法抛 `CacheException(CODEC_DECODE_FAILED)`，**不允许**当作 miss；
- `payload` 的大小建议 < 1 MB；超过时记 WARN（压缩列为后续）；
- 更换 codec 会导致旧数据解码失败；上线时配合 key 前缀版本或清空缓存。

---

## 3. Redis key 布局（`cache4j-central-redis`）

### 3.1 组成

```text
<globalPrefix>:<namespacePart>:<encodedKey>
```

- `globalPrefix`：部署级前缀，默认 `cache4j`；多环境/多租户可设为 `prod:cache4j`；
- `namespacePart`：
  - 默认（`hashTag=true`）：`{<namespace>}`，例如 `{user:detail}`；
  - 关闭 hash tag（`hashTag=false`）：`<namespace 的 UTF-8 字节长度>:<namespace>`，例如 `11:user:detail`；
- `encodedKey`：`KeyEncoder` 的输出（见 DESIGN §4.3）。

示例：

| 配置 | 逻辑 key | Redis key |
|---|---|---|
| `hashTag=true` | `user:detail` + `s2:42` | `cache4j:{user:detail}:s2:42` |
| `hashTag=false` | `user:detail` + `s2:42` | `cache4j:11:user:detail:s2:42` |
| 多环境前缀 | `user:detail` + `s2:42` | `prod:cache4j:{user:detail}:s2:42` |

### 3.2 保留 key

| 用途 | Redis key | 类型 |
|---|---|---|
| key 版本表 | `<prefix>:<namespacePart>:__rev` | hash（field = `encodedKey`，value = `<revision>|<expireAtMillis>`） |
| 热 key 当前窗口 | `<prefix>:<namespacePart>:__hot:w:<windowId>` | zset |
| 热 key 元信息 | `<prefix>:<namespacePart>:__hot:meta` | hash |

保留 key 统一使用 `__` 前缀，避免与 `KeyEncoder` 的输出冲突（`__rev` / `__hot:` 不可能由规范编码产生）。

版本表与数据 key 在同一个 hash tag 下，才能一次往返取回：

```text
HMGET cache4j:{user:detail}:__rev  s2:42
GET   cache4j:{user:detail}:s2:42
# 同一 hash tag，可用 pipeline 或一个 Lua 合成一次网络往返
```

> 为什么版本放 HASH 而不是每条 key 一个 `__rev:<encodedKey>`：一个 namespace 只多 1 个 Redis key（每条多一个会让 key 数量与元数据开销翻倍）；删除/清空都能用 `HDEL` / `DEL` 一次表达；`clear` 也不再需要"先 INCR 版本号、又不能让 SCAN 删掉它"这种自相矛盾的规则。

### 3.3 为什么默认加 hash tag

- Redis Cluster 的多 key 命令（`MGET`、`DEL` 多 key）与多 key Lua 都要求 key 在同一 slot；
- 用缓存名做 hash tag 后，同一个缓存的所有 key 落在同一 slot，`MGET` / 批量删除 / `HMGET`+`GET` 合成一次 Lua 天然可用；
- 热 key 的 ZSET + meta 也在同一 slot，Lua 脚本可以一次操作；
- 代价是热点缓存可能集中在一个 slot；如果某个缓存特别热，可以关闭 hash tag 并接受批量命令退化为逐 key（此时版本与值不再能一次 `MGET`，需要在同一次 pipeline 中发送）。

### 3.4 为什么关闭 hash tag 时要用长度前缀

如果直接拼 `cache4j:user:detail:...`，清理缓存 `user` 时前缀 `cache4j:user:` 会**误伤**缓存 `user:detail`。长度前缀（`4:user` vs `11:user:detail`）从编码上消除了这种前缀包含关系。

默认的 hash tag 形式也有同样保护：`{user}:` 不是 `{user:detail}:` 的前缀，因为 `}` 截断了。

### 3.5 清理前缀

`clear` 使用与写入完全一致的 `namespacePart`：

```text
hashTag=true  → cache4j:{user:detail}:
hashTag=false → cache4j:11:user:detail:
```

前缀必须以 `:` 结尾，且不能只按 namespace 的裸字符串匹配。`clear` 的动作是两步：

```text
1) DEL  <prefix>:__rev                              // 删整张版本表
2) SCAN MATCH <prefix>* COUNT <batch> + UNLINK      // 删数据 key（含 __hot:*）
```

版本表先删最安全：此后任何被访问到的 key 都会因"版本不存在 ≠ 本地副本的 revision"而回中央重取；没被再访问的副本靠本地 TTL 回收。不再有"必须先 INCR 一个 namespace 版本号、又要求清理前缀不能把它删掉"的矛盾。

---

## 4. `RedisCommands`：Redis 缓存 SPI（`cache4j-central-redis`）

### 4.1 为什么再抽一层

`cache4j-central-redis` 的定位是 **Redis 缓存 SPI**，不是"Redis 客户端层"：

1. 它要知道 key 怎么拼、value 怎么封装、负缓存怎么表达、批量读怎么写；
2. 它**不应该**知道连接是 Jedis、Lettuce、Redisson 还是 Spring Data Redis；
3. Spring 应用已经有连接池、集群、哨兵、SSL、ACL 配置，不应该为了缓存再维护一套。

因此把"命令"抽成最小 SPI，客户端实现留在适配模块：

```java
public interface RedisCommands extends AutoCloseable {

    Optional<byte[]> get(byte[] key);

    /** 顺序与入参一致；缺失为 null */
    List<byte[]> getAll(List<byte[]> keys);

    /** ttl 为 null 表示不设过期（仅在 allowNoExpiry 时由上层放行） */
    void set(byte[] key, byte[] value, @Nullable Duration ttl);

    /** SET NX + TTL；返回 true 表示写入成功 */
    boolean setIfAbsent(byte[] key, byte[] value, @Nullable Duration ttl);

    long del(byte[]... keys);

    /** 设置过期时间；用于窗口 ZSET / meta 的清理 */
    boolean expire(byte[] key, Duration ttl);

    /** 按前缀清理；实现可用 SCAN + DEL/UNLINK，非原子 */
    void clearByPrefix(byte[] prefix, int batchSize);

    /**
     * 执行 Lua；脚本、KEYS、ARGV 全部是字节。
     * 返回值按 Redis 协议映射：整数 → Long，批量字符串 → byte[]，数组 → List。
     * 所有原子操作（写值 + 更新版本、热 key 上报 + 状态计算）都走这里。
     */
    Object eval(byte[] script, List<byte[]> keys, List<byte[]> args);

    boolean ping();

    @Override
    void close();
}
```

约束：

- 所有方法阻塞式；超时由具体实现负责；
- key/value 都是 `byte[]`；字符串编码统一由 `RedisKeyFormatter` 与 `CacheCodec` 完成；
- `clearByPrefix` 允许非原子，但必须**只**清理指定前缀；
- `eval` 是版本与热 key 原子性的唯一依赖：实现必须保证脚本在 Redis 服务端原子执行，且 `EVALSHA` 回退 `EVAL` 的行为一致；
- `RedisCommands` 抛出的底层异常统一用 `RedisAccessException`（本模块定义的轻量异常，带 `Kind`）表达，`RedisCentralCache` 再翻译成 `CacheException`；Jedis / Lettuce / Redisson / Spring 的异常类型不得泄漏到上层。
- **命令超时不在本库配置**：`cache4j-central` 不持有连接，没有执行超时的位置。Spring 用户由 `spring.data.redis.timeout`（连接/命令级）控制；自实现 `RedisCommands` 的用户自行决定。建议把缓存命令配成 **200–500ms 快速失败**（让 `failOpen` 降级尽早生效），但本库不代为配置、也不在 `CentralCacheSpec` 里留占位字段。

```java
public class RedisAccessException extends RuntimeException {
    public enum Kind { UNAVAILABLE, TIMEOUT, COMMAND_FAILED }
    private final Kind kind;
}
```

### 4.2 实现矩阵

| 模块 | 实现 | 依赖 | 说明 |
|---|---|---|---|
| `cache4j-central-redis` | 无客户端实现 | 仅 `cache4j-central` | 定义 `RedisCommands`、`RedisCentralCache`、key/envelope；**不依赖任何 Redis 客户端** |
| `cache4j-spring-redis` | `SpringRedisCommands` | Spring Data Redis（底层客户端由用户选择） | 复用 `RedisConnectionFactory`，不引入第二套连接 |
| 未来 `cache4j-redis-*` | 由使用方或独立模块提供 | Jedis / Lettuce / Redisson | 只实现 `RedisCommands`，不进入 `cache4j-central-redis` |
| `cache4j-central` | `InMemoryCentralCache` | 无 | 测试 / 单机兜底，不跨进程 |

---

## 5. `RedisCentralCache`

### 5.1 依赖

```java
public final class RedisCentralCache<V> implements CentralCache<V> {

    private final RedisCommands commands;
    private final RedisKeyFormatter keys;     // globalPrefix / hashTag / namespace
    private final CentralCacheSpec spec;
    private final CacheCodec<V> codec;
    private final CacheStats stats;
    private final Clock clock;                // 版本纪元基值；测试可注入
}
```

`RedisKeyFormatter` 只做 key 拼接，不访问 Redis；它保证写入与清理使用同一套规则。

### 5.2 读（一次 I/O 同时取版本与值）

```java
VersionedLookup<V> lookupWithRevision(CacheKey key) {
    byte[] revKey  = keys.revision(key.namespace());   // HASH
    byte[] dataKey = keys.format(key);
    byte[] field   = key.encoded().getBytes(UTF_8);

    List<?> raw;
    try {
        // 同一 hash tag 下，一个 Lua 往返完成 HGET + GET
        raw = (List<?>) commands.eval(RedisScripts.READ_WITH_REVISION,
                List.of(revKey, dataKey), List.of(field));
    } catch (RedisAccessException e) {
        throw translate(e); // CENTRAL_UNAVAILABLE / CENTRAL_TIMEOUT
    }

    RevAndExpire rev = decodeRevision((byte[]) raw.get(0));   // "<rev>|<expireAt>"；field 缺失 → (0, 0)
    long revision = rev.revision();
    byte[] data = (byte[]) raw.get(1);

    if (data == null) {
        stats.storeMiss();
        return new VersionedLookup<>(revision, Lookup.miss());
    }

    Envelope env = Envelope.decode(data);         // magic/version/flags 校验
    if (env.negative()) {
        stats.storeNegativeHit();
        return new VersionedLookup<>(revision, Lookup.negative());
    }

    V value = codec.decode(env.payload());
    stats.storeHit();
    return new VersionedLookup<>(revision, Lookup.hit(value));
}
```

- **版本与值必须来自同一次往返**（同一个 Lua / pipeline），保证两者是同一时刻的观测；
- 版本 field 不存在时返回 `revision = 0`；编排层据此判定"副本的 revision 与中央不符"，自然回中央；
- 解码失败抛 `CODEC_DECODE_FAILED`，不返回 miss；
- 负缓存命中单独计数；
- > 这里的 `stats` 是**存储层口径**（`storeHitCount` / `storeMissCount`），不是业务命中率。业务层面的 `hitCount` / `missCount` 只由 `TwoLevelCache` 记（§4.6）。两者**不得相加**：一次业务读典型情况下会让 `storeHit=1` 且 `centralHitCount=1`，那是同一个事实的两个视角。

`lookupAllWithRevision` 用 `HMGET __rev field1 field2 ...` + `MGET data1 data2 ...`（同一 pipeline），逐个解码；顺序与入参一致；
- 普通 `lookup` 复用 `lookupWithRevision` 并丢弃版本，v1 不维护第二套逻辑。

### 5.3 写（值与版本同一 Lua）

```java
long putAndGetRevision(CacheKey key, V value, Duration ttl) {
    if (value == null) {
        return putNegativeAndGetRevision(key, ttl);
    }
    byte[] payload = codec.encode(value);         // 失败抛 CODEC_ENCODE_FAILED
    byte[] raw = Envelope.value(payload);
    return evalWrite(key, raw, effectiveTtl(ttl != null ? ttl : spec.ttl()));
}

long putNegativeAndGetRevision(CacheKey key, Duration ttl) {
    if (spec.nullTtl().isZero()) {
        evict(key);                              // 关闭负缓存时等价于删除
        return 0L;
    }
    return evalWrite(key, Envelope.negative(), effectiveTtl(spec.nullTtl()));
}

/** SET data + HSET __rev field="rev|expireAt"，同一 Lua 内完成，返回新版本 */
private long evalWrite(CacheKey key, byte[] raw, Duration ttl) {
    Object result = commands.eval(
            RedisScripts.WRITE_WITH_REVISION,
            List.of(keys.revision(key.namespace()), keys.format(key)),
            List.of(key.encoded().getBytes(UTF_8), raw, ttlArg(ttl),
                    nowArg(), expireAtArg(ttl)));
    return (Long) result;
}

/** 删除：DEL data + HDEL field，同一 Lua 内完成（不保留版本） */
public void evict(CacheKey key) {
    commands.eval(RedisScripts.EVICT,
            List.of(keys.revision(key.namespace()), keys.format(key)),
            List.of(key.encoded().getBytes(UTF_8)));
}

/** ttl == null 时返回空字节，表示不设过期 */
private byte[] ttlArg(Duration ttl) {
    return ttl == null ? new byte[0] : Long.toString(ttl.toMillis()).getBytes(UTF_8);
}

/** 时间戳版本：只要求每次都不同（Lua 内取 max(now, cur + 1)） */
private byte[] nowArg() {
    return Long.toString(clock.millis()).getBytes(UTF_8);
}

/** 数据 key 的过期时刻；无 TTL 时为 0（永不过期） */
private byte[] expireAtArg(Duration ttl) {
    long at = ttl == null ? 0L : clock.millis() + ttl.toMillis();
    return Long.toString(at).getBytes(UTF_8);
}
```

`WRITE_WITH_REVISION`（示意）：

```lua
-- KEYS[1] = __rev (hash), KEYS[2] = data
-- ARGV[1] = field(encodedKey), ARGV[2] = value, ARGV[3] = ttl millis ('' = 无过期),
-- ARGV[4] = now, ARGV[5] = expireAtMillis (0 = 永不过期)

local cur = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
local newRev = math.max(tonumber(ARGV[4]), cur + 1)   -- 保证每次都不同

if ARGV[3] == '' then
  redis.call('SET', KEYS[2], ARGV[2])
else
  redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
end

redis.call('HSET', KEYS[1], ARGV[1], newRev .. '|' .. ARGV[5])
return newRev
```

`EVICT`（示意）：

```lua
-- KEYS[1] = __rev (hash), KEYS[2] = data; ARGV[1] = field(encodedKey)
redis.call('DEL', KEYS[2])
redis.call('HDEL', KEYS[1], ARGV[1])
return 1
```

- 负缓存与正值共用同一个**数据 key**，后写覆盖前写；两者都更新该 key 的版本；
- `putIfAbsentAndGetRevision` 用同一套 Lua：`SET NX` 成功才 `HSET` 版本并返回新版本；已存在则返回 `empty` 且**不**更新；
- `evict` 在同一个 Lua 内 `DEL data` + `HDEL field`：**不保留版本**。删除仍对其他节点可见——对账时“本地有、中央缺字段”就是已删除；这样也避免了 `HLEN` 因删除而膨胀；
- `clear()` 先 `DEL __rev`，再 `SCAN + UNLINK` 数据 key（见 §3.5）；
- 数据按 TTL 自然过期时 field 会留着，但它自带的 `expireAt` 让**对账**能识别并 `HDEL`（§5.5），不需要额外清理策略；
- 不允许“先写值、再单独 `HSET`”：中间失败会导致其他节点永远感知不到这次写。

### 5.4 TTL 与抖动

```java
Duration effectiveTtl(Duration ttl) {
    if (ttl == null) {
        ttl = spec.ttl();                 // null 表示“用缓存名默认 TTL”
    }
    if (ttl.isNegative()) {
        throw new CacheException(INVALID_SPEC, "TTL 不能为负");
    }
    if (ttl.isZero()) {
        if (!spec.allowNoExpiry())
            throw new CacheException(INVALID_SPEC, "中央缓存不允许无 TTL");
        return null;                      // 仅显式 allowNoExpiry 时表示永不过期
    }

    long jitter = (long) (ttl.toMillis() * spec.ttlJitter() * (random.nextDouble() * 2 - 1));
    long millis = Math.max(1, ttl.toMillis() + jitter);
    return Duration.ofMillis(millis);
}
```

- Redis 使用毫秒精度（`PX`）；小于 1ms 向上取 1ms；
- 抖动默认 ±10%，避免大量 key 同时过期造成回源尖峰；
- 负缓存 TTL 同样抖动；
- 不单独发 `EXPIRE` 命令，`SET ... PX` 保证原子。

### 5.5 批量与清理

- `lookupAll` → `MGET`；
- `lookupAllWithRevision` → `HMGET __rev field1 field2 ...` + `MGET data1 data2 ...`（同一 pipeline 一次往返），每项自带该 key 的版本；
- 批量写可后续增加 pipeline；v1 先逐 key（`RedisCommands` 预留 `setAll` 扩展位）；
- `clear` → 先 `DEL __rev`，再 `SCAN MATCH <prefix>* COUNT <batch>` + `UNLINK`/`DEL` 分批；
- `__rev` 不需要额外清理策略：`evict` 已 `HDEL`；数据按 TTL 自然过期时，靠 field 自带的 `expireAt` 让**对账**识别并批量 `HDEL`（这就是 `expireAt` 存在的唯一理由）；
- `revisions(keys)` → `HMGET __rev field1 field2 ...`（按 `reconcileBatchSize` 分批），只取版本、不取数据值；
- `dropRevisions(keys)` → `HDEL __rev field1 field2 ...` 批量，或一个 Lua 逐批删；
- 禁止 `KEYS`；
- Cluster 下 `SCAN` 需要逐 master 节点执行；`RedisCommands.clearByPrefix` 负责屏蔽差异，客户端实现需按 Redis 部署形态遍历节点；
- `clear` 是运维操作，可能耗时；业务路径不要频繁调用。

### 5.6 故障翻译

| 底层 | 翻译 |
|---|---|
| 连接拒绝 / 连接断开 / 集群不可用 | `CACHE-00005 CENTRAL_UNAVAILABLE` |
| 命令超时 | `CACHE-00006 CENTRAL_TIMEOUT` |
| 其他 Redis 命令错误（如 OOM、READONLY） | `CACHE-00005`，message 保留原因 |
| 解码失败 | `CACHE-00004 CODEC_DECODE_FAILED` |
| 编码失败 | `CACHE-00003 CODEC_ENCODE_FAILED` |

- 不在 `RedisCentralCache` 内做重试；重试策略由具体客户端 / 连接工厂负责；
- 不做熔断（v1）；需要时在应用层或框架适配层加 Resilience4j 等；
- `failOpen` 的判断在 `TwoLevelCache` 完成。

### 5.7 热 key 协调（`RedisHotKeyCoordinator`）

`cache4j-central-redis` 同时提供 `HotKeyCoordinator` 的 Redis 实现，仍然只依赖 `RedisCommands`，不依赖任何客户端。参数来自各缓存的 `dynamic.global.*`（见 LOCAL §2），不放在 `cache4j.redis.*`。

#### 5.7.1 key 布局

```text
cache4j:{ns}:__hot:meta                 HASH
  <encodedKey>:active   -> lastActiveWindow
  <encodedKey>:hot      -> lastHotWindow
  <encodedKey>:lowSince -> 连续低活动起始窗口

cache4j:{ns}:__hot:w:<windowId>         ZSET
  member = <encodedKey>
  score  = 该窗口内的集群访问次数
```

- `windowId = floor(now / dynamic.global.window)`；
- 只保留当前与上一窗口，查询时两者相加，降低窗口边界抖动；
- 窗口 key 设置 `2 * dynamic.global.window` 的 TTL，自动清理；
- meta 设置比 `dynamic.global.staleWindows * dynamic.global.window` 更长的 TTL，自动清理长期不活跃的 key。

#### 5.7.2 一次 I/O 完成上报 + 状态

```java
public HotKeyReport reportAndStatus(String namespace, CacheKey key, long delta) {
    GlobalSpec g = globalSpecs.apply(namespace);
    if (g == null) {
        // namespace 未配置 dynamic.global：不猜阈值，返回 UNKNOWN 并记 WARN
        // （不计入门面的 hotReportDropped——那是编排层自己的账）
        return HotKeyReport.unknown();
    }
    long windowId = clock.millis() / g.window().toMillis();
    Object r = commands.eval(
            RedisScripts.HOT_REPORT_AND_STATUS,
            List.of(keys.hotMeta(namespace),
                    keys.hotWindow(namespace, windowId),
                    keys.hotWindow(namespace, windowId - 1)),
            args(key.encoded(), delta, windowId,
                 g.hotThreshold(), g.coolThreshold(),
                 g.demoteWindows(), g.staleWindows(),
                 windowTtlMillis(g.window()), metaTtlMillis(g)));
    return decodeReport(r);
}
```

`HOT_REPORT_AND_STATUS`（示意）：

```lua
-- KEYS[1] meta, KEYS[2] current window, KEYS[3] previous window
-- ARGV[1] key, ARGV[2] delta, ARGV[3] windowId,
-- ARGV[4] global hot threshold, ARGV[5] global cool threshold,
-- ARGV[6] demoteWindows, ARGV[7] staleWindows,
-- ARGV[8] windowTtlMillis, ARGV[9] metaTtlMillis

if tonumber(ARGV[2]) > 0 then
  redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[1])
  redis.call('PEXPIRE', KEYS[2], ARGV[8])
end

local cur  = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
local prev = tonumber(redis.call('ZSCORE', KEYS[3], ARGV[1]) or '0')
local total = cur + prev

local active = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':active') or '-1')
local hot    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':hot')    or '-1')
local low    = tonumber(redis.call('HGET', KEYS[1], ARGV[1] .. ':lowSince') or '-1')

-- 纯查询且中央从未见过该 key
if tonumber(ARGV[2]) == 0 and active < 0 and total == 0 then
  return { 'UNKNOWN', '0', '-1', '-1' }
end

local status = 'ACTIVE'

-- 长时间无上报：优先判 COOLING
if active >= 0 and tonumber(ARGV[3]) - active >= tonumber(ARGV[7]) then
  status = 'COOLING'
elseif total >= tonumber(ARGV[4]) then
  status = 'HOT'
  hot = tonumber(ARGV[3])
  low = -1
elseif total < tonumber(ARGV[5]) then
  if low < 0 then low = tonumber(ARGV[3]) end
  if tonumber(ARGV[3]) - low >= tonumber(ARGV[6]) then
    status = 'COOLING'
  end
else
  low = -1
end

redis.call('HSET', KEYS[1],
  ARGV[1] .. ':active', ARGV[3],
  ARGV[1] .. ':hot', hot,
  ARGV[1] .. ':lowSince', low)
redis.call('PEXPIRE', KEYS[1], ARGV[9])

return { status, tostring(total), tostring(active), tostring(hot) }
```

- 上报与查询合并为一次 `EVAL`，没有单独的"定时上报"；
- `delta = 0` 时仍然可以查询状态（用于补偿查询）；
- 返回 `HOT` / `ACTIVE` / `COOLING` / `UNKNOWN`；
- `UNKNOWN` 只在"本次 `delta == 0` 且中央从未见过该 key"时返回；任何一次 `delta > 0` 的上报都会让状态进入 `ACTIVE` 或 `HOT`。
- meta 的 TTL 用 `ARGV[9]`，比窗口 TTL 长；窗口 TTL 只清理 ZSET。

#### 5.7.3 批量

`reportAndStatusAll` 一次要处理**一整个分区**的热 key（≈ `maxHotKeys / hotReportPartitions`，硬上限 `hotReportBatchMax = 64`）：

1. **pipeline 逐 key 调用同一个 Lua**（推荐）：每个 key 一次 `EVAL`，N 个 key 合并成一次网络往返；
2. 一个 Lua 遍历 `ARGV` 里的多个 key：一次原子执行，但脚本长度与执行时间随 key 数线性增长。

v1 选 **pipeline**：单个 key 的脚本约 5–6 次 `redis.call`，64 key 一次原子执行会占 Redis 主线程数百微秒；pipeline 把成本摊到多个独立命令上，抖动更小。两种实现都必须：

- 每个 key 的状态计算仍然走同一个 `HOT_REPORT_AND_STATUS` 脚本（**不复制**第二套状态机）；
- 返回值与入参 key 一一对应（缺失项用 `UNKNOWN` 补齐）；
- 单次批量的 key 数不超过 `dynamic.hotReportBatchMax`（由编排层保证，存储层可再校验一次）。

> 为什么不能"一次只上报一个 key"：热 key 判定需要集群合计频次，单 key 上报会让计数被系统性低估（详细推算见 `DESIGN` §5.9.3 与 §8.5 的集群计数用例）。

#### 5.7.4 与版本机制的关系

- 版本同步与热 key 同步是**两条独立的访问驱动信号**；
- 版本同步面向"值是否过期"，热 key 同步面向"热度身份是否变化"；
- 两者可以共用同一次访问，但 SPI 分开，避免把 `STATIC` 也拖进热 key 逻辑；
- `cache4j-central-redis` 可以在内部把"版本 + 值 + 热度"合并成一个 Lua（可选优化），但对外仍暴露独立方法，保持语义清晰。

---

## 6. 客户端适配策略

### 6.1 `cache4j-central-redis` 不提供客户端

本模块的依赖只有 `cache4j-central`，没有 Jedis、Lettuce、Redisson、Spring Data Redis。它产出的是：

- `RedisCommands`：Redis 命令的最小接口；
- `RedisAccessException`：客户端异常的统一翻译目标；
- `RedisKeyFormatter`：key 布局；
- `Envelope`：值格式；
- `RedisCentralCache`：基于 `RedisCommands` 的纯逻辑实现；
- `RedisHotKeyCoordinator`：基于窗口 ZSET + Lua 的热 key 协调实现；
- `RedisCentralCacheProvider`：把一个 `RedisCommands` 实例装配成 `CentralCache`，并同时提供 `HotKeyCoordinator`。

```java
RedisKeyFormatter keys = RedisKeyFormatter.builder()
        .globalPrefix("cache4j")
        .hashTag(true)
        .build();

// 阈值按缓存名解析：不同 DYNAMIC 缓存可以有不同的 global.*
Function<String, GlobalSpec> globalSpecs = namespace -> specs.globalOf(namespace);

RedisCentralCacheProvider provider =
        new RedisCentralCacheProvider(commands, keys,
                new RedisHotKeyCoordinator(commands, keys, globalSpecs));

// DYNAMIC + GLOBAL 时使用；与 CentralCache 共享同一个 RedisCommands
HotKeyCoordinator coordinator = provider.hotKeyCoordinator().orElseThrow();
```

- `provider.create(spec, codec)` 返回 `RedisCentralCache` 视图；
- `provider.hotKeyCoordinator()` 返回构造时传入的协调器；未传入则为 `Optional.empty()`；
- 协调器通过 `Function<String, GlobalSpec>` 解析每个 namespace 的窗口与阈值，不维护第二份配置；
- 某个 namespace 没有 `GlobalSpec` 时，`reportAndStatus` 返回 `UNKNOWN` 并记 WARN（不猜测阈值）；
- `provider.close()` 关闭 `RedisCommands`；实现必须幂等；
- 多个视图共享同一个 `RedisCommands`；视图的 `close()` 不关闭命令层；
- 谁创建 `RedisCommands`，谁负责它的连接配置与生命周期。

### 6.2 客户端实现要求

任何 `RedisCommands` 实现（Spring 或其他）必须满足：

| 要求 | 说明 |
|---|---|
| `get` / `getAll` | `GET` / `MGET`；缺失返回 `Optional.empty()` / `null` |
| `set` | `SET key value PX <ms>`；`ttl=null` 时才允许无过期 |
| `setIfAbsent` | `SET key value PX <ms> NX`；返回是否写入 |
| `del` | `DEL` 或 `UNLINK`；批量删除 |
| `hmget` | `HMGET <hash> field...`；返回顺序与入参一致，缺失为 `null`（对账读版本用） |
| `hdel` | `HDEL <hash> field...`；批量删 field（对账清死版本用） |
| `expire` | `PEXPIRE`；用于热 key 窗口/meta |
| `eval` | `EVAL` / `EVALSHA`；参数与返回值均为原始字节/整数；服务端原子执行 |
| `clearByPrefix` | `SCAN MATCH <prefix>* COUNT <batch>` + 分批删除；禁止 `KEYS` |
| `ping` | `PING`；失败返回 false，不抛异常 |
| 异常 | 统一抛 `RedisAccessException`，标注 `UNAVAILABLE` / `TIMEOUT` / `COMMAND_FAILED` |
| 超时 | 由客户端配置；缓存命令建议 200–500ms 快速失败 |
| 线程安全 | 实现必须可被多个缓存实例并发调用 |

### 6.3 为什么这样切

- `cache4j-central-redis` 可以在**没有任何 Redis 容器、没有任何客户端**的情况下用 `FakeRedisCommands` 做完整单测；
- Spring 应用复用 Spring Data Redis 的连接池/集群/哨兵，不再引入第二套客户端；
- 未来要支持 Jedis / Lettuce / Redisson，只需新增 `cache4j-redis-*` 适配模块实现 `RedisCommands`，不需要改 `cache4j-central-redis`；
- 避免"缓存库为了支持 Redis，被迫把某个客户端版本带进所有使用方"。

### 6.4 非 Spring 使用

v1 **不提供**独立的 Jedis / Lettuce 适配模块。非 Spring 使用方有两种选择：

1. 自行实现 `RedisCommands`（命令集很小，见 §6.2）；
2. 等待后续按需新增 `cache4j-redis-lettuce` / `cache4j-redis-jedis` / `cache4j-redis-redisson`；这些模块同样只实现 `RedisCommands`，不会反向进入 `cache4j-central-redis`。

---

## 7. 与 Spring 的边界（`cache4j-spring-redis`）

### 7.1 原则

- `cache4j-central-redis` **不出现** `org.springframework.*` 依赖，也不出现任何 Redis 客户端依赖；
- Spring 应用的 Redis 连接、池、集群、哨兵、SSL 全部复用 Spring Data Redis 的 `RedisConnectionFactory`；
- `cache4j-spring-redis` 只实现 `RedisCommands`，把 Spring 连接适配成 cache4j 的最小命令集；
- `cache4j-spring-redis` **不锁定底层客户端**：应用使用 Jedis、Lettuce 还是其他实现，由 Spring Data Redis 的依赖与 `spring.data.redis.*` 配置决定；
- **不经过 `RedisTemplate` 的 value serializer**：直接用 `RedisConnection` 的 `byte[]` 命令，避免"业务 codec 序列化一次 + RedisTemplate 再序列化一次"。

### 7.2 `SpringRedisCommands`

```java
public final class SpringRedisCommands implements RedisCommands {

    private final RedisConnectionFactory factory;

    @Override
    public Optional<byte[]> get(byte[] key) {
        try (RedisConnection c = factory.getConnection()) {
            return Optional.ofNullable(c.stringCommands().get(key));
        }
    }

    @Override
    public void set(byte[] key, byte[] value, Duration ttl) {
        try (RedisConnection c = factory.getConnection()) {
            c.stringCommands().set(key, value,
                    Expiration.milliseconds(ttl.toMillis()),
                    SetOption.upsert());
        }
    }

    // setIfAbsent / del / mGet / scan / ping 同理
}
```

- `RedisConnection` 不是线程安全的；每次操作从工厂取、用完 `close()` 归还；
- 连接池/共享原生连接由 Spring 负责，cache4j 不再建池；
- `clearByPrefix` 用 `keyCommands().scan(ScanOptions...)`；cluster 连接需要逐节点处理；
- `ping` 用 `connection.ping()`；
- 底层 `RedisConnectionFailureException` / `RedisSystemException` 统一翻译成 `RedisAccessException`，再由 `RedisCentralCache` 转 `CacheException`。

### 7.3 自动装配

- `cache4j-spring-redis` 提供 `Cache4jSpringRedisAutoConfiguration`：
  - `@ConditionalOnClass(RedisConnectionFactory.class)`
  - `@ConditionalOnBean(RedisConnectionFactory.class)`
  - `@ConditionalOnProperty(prefix="cache4j.central", name="type", havingValue="redis")`
  - `@AutoConfigureAfter(RedisAutoConfiguration.class)`
- 复用 Spring Boot 的 `spring.data.redis.*`（host/port/password/ssl/cluster/sentinel/pool）；cache4j 只增加 `cache4j.redis.*`（keyPrefix / hashTag / clearBatchSize）；
- 只注册一个 `RedisCommands` Bean，并据此创建 `RedisCentralCacheProvider`；不创建第二个连接工厂；
- 不覆盖用户自定义的 `RedisConnectionFactory`；
- `cache4j-spring-redis` 只依赖 `spring-data-redis` API，不依赖 starter，也不依赖 Jedis / Lettuce；底层客户端由用户引入；
- 底层客户端由用户选择：应用引入 `spring-boot-starter-data-redis`（默认 Lettuce）或改用 Jedis，`SpringRedisCommands` 均不感知；
- `cache4j-spring` 不依赖 `cache4j-spring-redis`；后者依赖前者，避免循环。

### 7.4 序列化边界

| 层 | 负责 |
|---|---|
| 业务 | 提供 `CacheCodec<V>`（如 JSON / JDK） |
| `cache4j-central-redis` | 加 envelope、拼 key、命令语义 |
| `cache4j-spring-redis` | 只做连接适配，不碰序列化 |
| Spring Data Redis | 提供连接；客户端（Jedis / Lettuce / 其他）由用户选择；**不**使用其 value serializer |

这样可以保证 Redis 里的数据格式由 cache4j 决定，不会因为 Spring 的 `RedisTemplate` 配置变化而读不出来。

---

## 8. 内存中央缓存（`cache4j-central`）

`InMemoryCentralCache`：

- `ConcurrentHashMap<CacheKey, Entry>`；
- `Entry` 保存 envelope 的语义（正值/负值）与 `expireAtNanos`；
- 每个 namespace 维护一个 `AtomicLong revision`，与写操作在同一把锁内递增；
- `lookupWithRevision` 在同一锁内读取版本与值，保证快照一致；
- 惰性过期 + 可选定时清理；
- `clear` 按 namespace 清理并递增版本；
- `ping` 恒为 true；
- 附带 `InMemoryHotKeyCoordinator`：窗口计数 + 状态机，供单机 / 测试使用；
- 不跨进程，仅用于：
  - `cache4j-core` 的 `TwoLevelCache` 单元测试；
  - 单机开发/演示；
  - 没有 Redis 时的显式降级（必须配置 `central.type=memory`，不能静默 fallback）。

生产环境必须显式选择 Redis；框架适配器在既没有 Redis 也没有显式 `memory` 配置时，应在启动阶段报配置错误，而不是悄悄用内存缓存。

---

## 9. 运维建议

| 项 | 建议 |
|---|---|
| 内存策略 | 专用 Redis 建议 `allkeys-lru` 或 `allkeys-lfu`；所有 cache key 都有 TTL，`volatile-*` 也可 |
| 持久化 | 缓存实例可关闭 RDB/AOF；开启也不影响 TTL |
| 监控 | `used_memory`、`evicted_keys`、`keyspace_hits/misses`、命令 P99、连接数 |
| 大 key | value > 1MB 记 WARN；避免把大对象整体塞进缓存 |
| 热 key | 监控单 key QPS；必要时用 `DYNAMIC` 本地缓存削峰 |
| 版本表 | `__rev` 是 HASH（field = 编码后的 key，value = `rev|expireAt`）：`evict` 直接 `HDEL`，TTL 过期的死 field 由对账清；监控 `HLEN` 应接近存活 key 数 |
| 热 key 窗口 | `__hot:w:*` 与 `__hot:meta` 有 TTL，允许被淘汰；丢失后状态重新从 `UNKNOWN` 开始 |
| Cluster | 默认 hash tag 保证同缓存同 slot；`clear` 逐节点 SCAN，非原子 |
| 安全 | `rediss://` + ACL；不要用默认密码；key 前缀隔离环境 |
| 版本升级 | codec 变更时 bump `globalPrefix` 或清空；不要假设旧数据可解码 |

---

## 10. 测试

### 10.1 契约测试

同一套 `CentralCache` 契约跑 `InMemoryCentralCache` 与 `RedisCentralCache`：

- `RedisCentralCache` 使用测试目录下的 `FakeRedisCommands`（内存 Map + TTL + Lua 白名单），**不需要 Redis 容器、不需要任何客户端**；
- 命中 / 未命中 / 负命中；
- `put` / `putIfAbsent` / `evict` / `clear`；
- TTL 到期；
- 批量 `lookupAll` 顺序与缺失；
- `ping`；
- 并发 `putIfAbsent` 只有一个成功；
- 写操作返回该 key 的新版本，且**每次写都不同**（同一毫秒连续两次写也不相等）；
- 不同 key 的版本互不影响（写 A 不改 B 的版本）；
- `putIfAbsent` 未写入时不更新版本；
- `lookupWithRevision` 的版本与值来自同一次往返；
- 版本 field 缺失时返回 `(0, 0)`；`evict` 后该 field 消失（删除对账可见）；
- `clear` 后版本表消失，后续读返回 revision `0`；
- `revisions(keys)` 批量取版本：顺序一致、缺失为 `null`、不拉数据值；

### 10.2 Redis 语义专项（`cache4j-central-redis`，无客户端）

- envelope 黄金字节：magic/version/flags/payload；
- key 布局：hash tag 开/关、globalPrefix、长度前缀、清理前缀不误伤兄弟 namespace；
- 保留 key：`__rev` / `__hot:w:<id>` / `__hot:meta` 不与业务 key 冲突；
- TTL 抖动范围；
- `clearByPrefix` 只把目标前缀传给 `RedisCommands`；
- 版本 Lua：`SET data` + `HSET field="rev|expireAt"` 原子；失败不更新；返回值为新版本；同一毫秒两次写得到不同值；
- `EVICT` Lua：`DEL data` + `HDEL field` 原子，且**不**留下任何版本痕迹；
- 热 key Lua：上报与状态一次返回；窗口边界（当前 + 上一窗口）；`COOLING` 的两种触发（低活动、长时间无上报）；
- `eval` 返回值映射：整数 → `Long`，字符串 → `byte[]`，数组 → `List`。
- `RedisAccessException.Kind` → `CacheException` 的翻译；
- `FakeRedisCommands` 模拟断连/超时/命令失败；
- 本模块的 `pom.xml` 架构测试：不得出现 Jedis / Lettuce / Redisson / Spring。

### 10.3 Spring 适配专项（`cache4j-spring-redis`，真实 Redis）

- `RedisConnectionFactory` 存在时自动装配 `SpringRedisCommands`；
- 不创建第二个连接工厂；
- 不经过 `RedisTemplate` 序列化：用自定义 codec 写入后，直接 `GET` 校验字节格式；
- Spring Data Redis 的 Jedis / Lettuce 连接工厂均可工作；
- `clearByPrefix` 在 standalone / cluster 下的行为（cluster 标记为 best-effort）；
- 连接断开、超时、READONLY 错误的翻译；
- Testcontainers Redis 7，默认不参与普通 `mvn test`。

---

## 11. 扩展点

| 扩展 | 方式 |
|---|---|
| 其他 Redis 客户端（Redisson / Jedis） | 实现 `RedisCommands`，复用 `RedisCentralCache` |
| 其他中央存储（Memcached / 对象存储） | 实现 `CentralCache` / `CentralCacheProvider` |
| 压缩 | 在 envelope 的 flags 增加压缩位，`RedisCentralCache` 层处理 |
| 逻辑清空 | per-key 版本 + 批量对账：`clear` 删掉版本表（`DEL __rev`）后，下一轮对账会发现“中央缺字段”并清掉副本 |
| 热 key 协调 | 实现 `HotKeyCoordinator`；Redis 实现位于 `cache4j-central-redis` |
| 指标 | 包装 `CentralCache`，或在框架适配器读取 `CacheStatsSnapshot` |
