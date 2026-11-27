package com.asialjim.cache4j.central;

import com.asialjim.cache4j.Cache4jKey;
import com.asialjim.cache4j.Lookup;
import com.asialjim.cache4j.level.CentralCache4j;
import com.asialjim.cache4j.strategy.CacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 中央缓存的骨架：把 {@link CentralCache4j} 的六个方法写成<strong>模板</strong>，子类只实现原语。
 *
 * <h2>泛型边界：本类只认识 {@code V}</h2>
 * <p>原语的参数与返回值全是强类型 {@code V}。<strong>本类不出现任何
 * {@code byte[]} / {@code ByteBuffer} / 序列化接口</strong>——
 * "值怎么变成字节"是存储层的私事（谁存储，谁转换）。</p>
 *
 * <pre>
 * core    CentralCache4j&lt;V&gt;            契约
 * central AbstractCentralCache4j&lt;V&gt;    模板（仍然是 V）
 * redis   RedisCentralCache4j&lt;V&gt;       原语实现（这里才出现 byte[]）
 * </pre>
 *
 * <h2>本类负责的四件事</h2>
 * <ol>
 *   <li><strong>TTL 决策</strong>：正/负副本用不同 TTL、加抖动防雪崩
 *       （全部委托 {@link CentralTtl4j}）；</li>
 *   <li><strong>失败降级</strong>：{@code failOpen=true} 时把存储异常吞成 miss，
 *       让上层回源；{@code false} 时原样抛出；</li>
 *   <li><strong>同步/异步写的区分</strong>：{@code putSync} 走原语本身，
 *       {@code put} 走可覆写的 {@link #writeAsync}；</li>
 *   <li><strong>回源写回</strong>：{@link #get} 未命中时调 loader 并写回中央。</li>
 * </ol>
 *
 * <h2>本类刻意<strong>不做</strong>的事</h2>
 * <ul>
 *   <li>不刷新版本号 —— {@link #writeRaw} 的契约本身就是"写值 + 更新版本"，
 *       由子类用一次原子操作完成（Redis 侧即一段 Lua）。抽象层若再拆一层
 *       "写值"和"写版本"，中间就有了不一致窗口。</li>
 *   <li>不碰字节、不碰客户端、不碰过期命令。</li>
 * </ul>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public abstract class AbstractCentralCache4j<V> implements CentralCache4j<V> {

    private static final Logger log = LoggerFactory.getLogger(AbstractCentralCache4j.class);

    @Override
    public final Lookup<V> lookup(Cache4jKey key, CacheStrategy strategy) {
        return guard(strategy, () -> {
            Lookup<V> lookup = readRaw(key, strategy);
            return lookup == null ? Lookup.miss() : lookup;
        }, Lookup.miss());
    }

    @Override
    public final V get(Cache4jKey key, Supplier<V> valueLoader, CacheStrategy strategy) {
        Lookup<V> lookup = lookup(key, strategy);
        if (!lookup.isMiss()) {
            // 命中（含负副本）直接返回，不再回源
            return lookup.optional().orElse(null);
        }
        if (valueLoader == null) {
            return null;
        }
        V value = valueLoader.get();
        // 回源结果写回中央：下一次其它节点/本节点的请求就不必再穿透
        put(key, value, strategy);
        return value;
    }

    @Override
    public final void put(Cache4jKey key, V value, CacheStrategy strategy) {
        long ttlSeconds = CentralTtl4j.ttlSeconds(value, strategy);
        guardVoid(strategy, () -> writeAsync(key, value, ttlSeconds, strategy));
    }

    @Override
    public final void putSync(Cache4jKey key, V value, CacheStrategy strategy) {
        long ttlSeconds = CentralTtl4j.ttlSeconds(value, strategy);
        guardVoid(strategy, () -> writeRaw(key, value, ttlSeconds, strategy));
    }

    @Override
    public final void evict(Cache4jKey key, CacheStrategy strategy) {
        guardVoid(strategy, () -> deleteRaw(key, strategy));
    }

    @Override
    public final void clear(CacheStrategy strategy) {
        guardVoid(strategy, () -> deleteNamespace(strategy.namespace(), strategy));
    }

    // ------------------------------------------------------------------ 原语（子类实现）

    /**
     * 读一条。
     *
     * @param key      缓存键
     * @param strategy 策略
     * @return {@link Lookup#hit} / {@link Lookup#negative} / {@link Lookup#miss}
     */
    protected abstract Lookup<V> readRaw(Cache4jKey key, CacheStrategy strategy);

    /**
     * 同步写一条，<strong>必须同时更新版本号</strong>。
     *
     * <p>"写值"与"更新版本"要在一个原子操作里完成，否则对账会读到
     * "值已新、版本还是旧"的中间态，把本地副本误判为新鲜。</p>
     *
     * @param key        缓存键
     * @param value      值；{@code null} 表示负副本
     * @param ttlSeconds 存活时长（秒）；{@link CentralTtl4j#NO_EXPIRY} 表示不设过期
     * @param strategy   策略
     */
    protected abstract void writeRaw(Cache4jKey key, V value, long ttlSeconds, CacheStrategy strategy);

    /**
     * 异步写一条。
     *
     * <p>默认直接委托 {@link #writeRaw}——"异步"是存储层<strong>可选</strong>的优化，
     * 不是必须的语义。子类若有 pipeline / 批量队列，可以覆写它把写操作攒起来。</p>
     *
     * <p>覆写时要保证：无论如何都<strong>不能丢写</strong>。异步化是为了吞吐，
     * 不是为了"尽力而为"——缓存写丢了，版本就不会前进，其它节点会一直读旧副本。</p>
     *
     * @param key        缓存键
     * @param value      值
     * @param ttlSeconds 存活时长（秒）
     * @param strategy   策略
     */
    protected void writeAsync(Cache4jKey key, V value, long ttlSeconds, CacheStrategy strategy) {
        writeRaw(key, value, ttlSeconds, strategy);
    }

    /**
     * 删一条。
     *
     * @param key      缓存键
     * @param strategy 策略
     */
    protected abstract void deleteRaw(Cache4jKey key, CacheStrategy strategy);

    /**
     * 清空整个 namespace：把 {@code C4J-L2:{namespace}:#{hash}:} 前缀下的
     * <strong>全部 key 删掉，不分类</strong>。
     *
     * <p>数据、版本、热度三类 key 都在这个前缀下（见 {@link CentralKeyLayout4j}）。
     * 既然目标就是全清，就按前缀一把扫掉——<strong>不需要区分类别，也不需要排顺序</strong>。</p>
     *
     * <p><strong>无返回值</strong>：清空是幂等的运维动作，删了 0 个和删了 10 万个都叫"已清空"，
     * 调用方也不该基于删除条数做任何判断。</p>
     *
     * <h2>这是一个<strong>合法且常见</strong>的业务操作</h2>
     * <p>清空整个 namespace 不是"运维专属的危险动作"，业务代码里本来就会用到。
     * 典型例子是 Spring Cache：</p>
     * <pre>{@code
     * @CacheEvict(cacheNames = "user:detail")          // 没给 key = 清空整个 cache
     * public void importUsers(List<User> users) { ... }
     * }</pre>
     * <p>批量导入后全量失效、配置变更后整块刷新、数据迁移后重建——这些都需要它，
     * 而且都是正当需求。所以本库不会、也不该对它设任何额外门槛。</p>
     *
     * <h2>但它影响面是整个 namespace</h2>
     * <p>与 {@code evict(key)} 只动一条不同，清空之后所有节点、所有 key 的本地副本
     * 都会在各自的对账周期内被清掉，且<strong>不可撤回</strong>。这更接近
     * SQL 的 {@code DELETE FROM table}（不带 {@code WHERE}）——是有意为之的批量操作，
     * 不是误操作，但要知道自己在做什么。</p>
     *
     * <p>因此建议：调用点最好<b>集中且可审计</b>，而不是散落在各种失败分支里。
     * 框架不阻止，但代码评审时"这行该不该存在"应当有据可依。</p>
     *
     * <p><strong>无返回值</strong>：清空是幂等动作，删了 0 个和删了 10 万个都叫"已清空"，
     * 调用方也不该基于删除条数做任何判断。</p>
     *
     * <h2>为什么必须分批（与频率无关）</h2>
     * <p>清空是低频操作，慢一点没人抱怨——所以<strong>不必优化延迟</strong>。
     * 但要分批的原因不是效率，而是<strong>不能把实例打挂</strong>：
     * 低频操作打挂一次实例，代价远高于把它分成十批慢慢删。
     * "低频" 不等于 "可以阻塞"。</p>
     *
     * <h2>这是前缀范围操作，不是 key 枚举</h2>
     * <p>namespace 固定，前缀就固定（{@code C4J-L2:{namespace}:#{hash}:}），
     * 因此只需要"删掉这个前缀下的全部 key"，<strong>不需要知道库里还存了什么</strong>。
     * 存储只要能按前缀定位即可；能列举全量 key 反而不是必要条件。</p>
     *
     * <h2>为什么不能靠 {@code HKEYS __rev} 拿清单</h2>
     * <p>版本 HASH 的 field 只在<strong>写入</strong>时添加，而数据 key 因 TTL 到期消失时
     * <strong>不会</strong>自动 {@code HDEL}——HASH field 没有独立 TTL。于是：</p>
     * <ul>
     *   <li><strong>会多</strong>：返回一批幽灵 key（版本还在、数据早过期）；</li>
     *   <li><strong>会少</strong>：若有实现没能在同一次原子写里更新版本，数据在而 field 缺。</li>
     * </ul>
     * <p>两个方向都失真。按前缀在存储里直接定位（Redis 的 {@code SCAN MATCH prefix*}、
     * etcd/TiKV 的 range delete、Hazelcast 的 predicate）才是可靠的做法——
     * 这是<strong>范围操作</strong>，不需要先枚举出全量 key。</p>
     *
     * <h2>性能：这一条比正确性更容易出事</h2>
     * <p>清空是"一次操作删掉任意多个 key"，而 Redis 是<strong>单线程执行命令</strong>的。
     * 写得不对不是慢一点，是会<strong>把整个实例卡住</strong>。以下几条都是硬约束：</p>
     *
     * <table border="1">
     *   <caption>Redis 适配层必须遵守</caption>
     *   <tr><th>约束</th><th>违反的后果</th></tr>
     *   <tr><td><strong>不要在一个 Lua 里删完整个 namespace</strong></td>
     *       <td>Lua 执行期间 Redis 不处理任何其它命令。脚本超过
     *           {@code lua-time-limit}（默认 5 秒）后，Redis 只会返回 {@code BUSY}——
     *           业务侧看到的是一批超时错误，而脚本还在跑</td></tr>
     *   <tr><td><strong>前缀迭代的 cursor 循环放在客户端</strong></td>
     *       <td>每批之间 Redis 能处理其它请求。放进 Lua 里循环就退化成"一个长脚本"，
     *           等于把最容易卡死的那一种写法包装了一遍</td></tr>
     *   <tr><td><strong>每批控制在千级</strong></td>
     *       <td>{@code SCAN COUNT} 只是提示而非保证，实际可能一次返回更多；
     *           拿到之后仍需在客户端按千级切片再 {@code UNLINK}，避免单条命令参数过多</td></tr>
     *   <tr><td><strong>用 {@code UNLINK} 而不是 {@code DEL}</strong></td>
     *       <td>{@code DEL} 同步释放内存，大 value 会让这一批的耗时突然放大；
     *           {@code UNLINK} 把释放挪到后台线程，每批耗时只与 key 数量成正比</td></tr>
     *   <tr><td><strong>集群下只需扫一个节点</strong></td>
     *       <td>同一 hash tag 保证本 namespace 的 key 全在同一槽位；但要注意
     *           <strong>不能</strong>假设 {@code SCAN} 会自动跨节点——扫错节点等于什么都没删</td></tr>
     * </table>
     *
     * <p>参考流程（每步都在客户端，Redis 只执行短命令）：</p>
     * <pre>{@code
     * cursor = 0
     * do {
     *     batch = SCAN cursor MATCH {dataKeyPrefix}* COUNT 1000
     *     cursor = batch.cursor
     *     for (chunk of split(batch.keys, 1000)) {
     *         UNLINK chunk...
     *     }
     * } while (cursor != 0)
     * }</pre>
     *
     * <p>如果某个 namespace 大到正常清空会造成可感知的抖动，就在批与批之间加一点间隔
     * （限速）——把影响摊平，比"快点删完"重要。清空是低频运维动作，慢一点没人会抱怨。</p>
     *
     * <h2>原子性：这里做不到严格原子，但也不需要</h2>
     * <p>严格原子要求"要么全删、要么全不删"，而 {@code SCAN} 天然是多批的。
     * 但全删语义下<strong>中间态是安全的</strong>：</p>
     * <table border="1">
     *   <caption>任意中间态的影响</caption>
     *   <tr><th>已删</th><th>未删</th><th>后果</th></tr>
     *   <tr><td>数据</td><td>版本</td>
     *       <td>其他节点对账看到版本匹配，继续用本地副本；本节点回源会 miss 并重新加载。
     *           短暂不一致，<strong>不会返回已删数据</strong></td></tr>
     *   <tr><td>版本</td><td>数据</td>
     *       <td>其他节点对账发现版本缺失，删本地副本；残留数据无人读到，等 TTL 消失</td></tr>
     * </table>
     * <p>两种中间态都不会造成"返回已被清除的数据"——那才是真正要防的错误。
     * 所以分批删除是合适的；为了形式上的原子性去背一个阻塞实例的大脚本，是拿稳定性换一个
     * 并不存在的收益。</p>
     *
     * <p>如果存储本身支持按前缀/范围<strong>原子且低开销</strong>地删除（某些 KV 的 prefix delete），
     * 直接用它，比 {@code SCAN} 更好。</p>
     *
     * @param namespace 命名空间
     * @param strategy  策略
     */
    protected abstract void deleteNamespace(String namespace, CacheStrategy strategy);

    // ------------------------------------------------------------------ 失败降级

    private <T> T guard(CacheStrategy strategy, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            if (!failOpen(strategy)) {
                throw e;
            }
            // 降级：存储不可用时当作 miss，让上层去回源。
            // 缓存挂了不该让业务跟着挂 —— failOpen 的全部意义就在这里。
            log.warn("[central]-lookup-failOpen namespace={} reason={}", strategy.namespace(), e.toString());
            return fallback;
        }
    }

    private void guardVoid(CacheStrategy strategy, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (!failOpen(strategy)) {
                throw e;
            }
            // 写失败只丢一次缓存推进，业务数据本身不受影响，降级为告警即可
            log.warn("[central]-write-failOpen namespace={} reason={}", strategy.namespace(), e.toString());
        }
    }

    private static boolean failOpen(CacheStrategy strategy) {
        return Optional.ofNullable(strategy.failOpen()).map(Supplier::get).orElse(true);
    }
}
