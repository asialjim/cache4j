package com.asialjim.cache4j.test;

import com.asialjim.cache4j.core.strategy.CacheStrategyDataSource;
import com.asialjim.cache4j.core.strategy.Page;
import com.asialjim.cache4j.core.strategy.StoredPolicy;
import com.asialjim.cache4j.core.strategy.TextPolicyCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 策略数据源的<strong>本地文件</strong>实现（测试/参考实现）。
 *
 * <p>生产上策略通常落在业务库（MySQL）或配置中心（Nacos / Apollo）；本类用文件，是为了在
 * 不引入 ORM / 客户端 SDK 的前提下，把"枚举声明 → 仓库 → 持久化"这条链路完整跑通，
 * 并且让人能直接 {@code cat} 出来看。</p>
 *
 * <h2>存储形态</h2>
 *
 * <pre>
 * cache4j-spring-redis-test/db/strategy.dat
 * ------------------------------------------------------------------
 * schema=1
 * namespace=decl:static
 * description=静态本地缓存（仓库落库测试）
 * strategy=STATIC
 * ...
 * ---
 * schema=1
 * namespace=decl:none
 * ...
 * </pre>
 *
 * <p>一条策略一段文本，段之间用单独一行 {@code ---} 分隔。直接 {@code cat} 就能读懂。</p>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>写入用"临时文件 + 原子移动"，进程被杀不会留下半截文件；</li>
 *   <li>并发控制是 {@code synchronized}（单 JVM）。跨进程同时改策略需要外部协调
 *       （文件锁 / 单人运维），本实现不声称支持；</li>
 *   <li>{@link #query} 天然全量读——文件里是<strong>策略</strong>（几十到几百条量级），
 *       不是业务数据，内存分页可以接受。真正的数据源（SQL）仍应把分页下推；</li>
 *   <li>{@link #update} 的 CAS 在 {@code synchronized} 内比较 {@code configVersion}，
 *       单 JVM 内是原子的。</li>
 * </ul>
 *
 * @author Asial Jim
 */
public class FileStrategyDataSource implements CacheStrategyDataSource {

    /**
     * 默认目录：模块下的 {@code db}（相对进程工作目录）。
     *
     * <p>Maven Surefire 与 IDEA 跑测试时工作目录都是模块根，所以实际落在
     * {@code cache4j-spring-redis-test/db/}。该目录已在 {@code .gitignore} 里忽略。</p>
     */
    public static final Path DEFAULT_DIRECTORY = Path.of("db");

    /**
     * 文件名。
     */
    public static final String FILE_NAME = "strategy.dat";

    /**
     * 记录分隔符（单独一行）。
     */
    private static final String SEPARATOR = "\n---\n";

    private final Path file;

    private final TextPolicyCodec codec = new TextPolicyCodec();

    /**
     * 用默认目录 {@code db}（相对工作目录，不存在则创建）。
     */
    public FileStrategyDataSource() {
        this(DEFAULT_DIRECTORY);
    }

    /**
     * @param directory 存放目录（不存在则创建）
     */
    public FileStrategyDataSource(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("创建策略目录失败：" + directory, e);
        }
        this.file = directory.resolve(FILE_NAME);
    }

    @Override
    public Optional<StoredPolicy> load(String namespace) {
        return Optional.ofNullable(readAll().get(namespace));
    }

    @Override
    public Page<StoredPolicy> query(String keyword, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        String needle = keyword == null ? "" : keyword.trim();

        List<StoredPolicy> matched = new ArrayList<>();
        for (StoredPolicy policy : readAll().values()) {
            if (needle.isEmpty()
                    || policy.namespace().contains(needle)
                    || (policy.description() != null && policy.description().contains(needle))) {
                matched.add(policy);
            }
        }
        matched.sort(Comparator.comparing(StoredPolicy::namespace));
        int from = (int) Math.min((long) safePage * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new Page<>(matched.subList(from, to), safePage, safeSize, matched.size());
    }

    @Override
    public synchronized boolean register(StoredPolicy policy) {
        Map<String, StoredPolicy> all = readAll();
        if (all.containsKey(policy.namespace())) {
            return false;
        }
        all.put(policy.namespace(), policy);
        writeAll(all.values());
        return true;
    }

    @Override
    public synchronized OptionalLong update(StoredPolicy policy, long expectedConfigVersion) {
        Map<String, StoredPolicy> all = readAll();
        StoredPolicy current = all.get(policy.namespace());
        if (current == null || current.configVersion() != expectedConfigVersion) {
            return OptionalLong.empty();
        }
        long newVersion = expectedConfigVersion + 1;
        all.put(policy.namespace(), new StoredPolicy(
                policy.namespace(), policy.description(), policy.strategy(),
                policy.maxSize(), policy.localTtl(), policy.localNullTtlDivisor(),
                policy.version(), policy.dynamic(),
                policy.centralTtl(), policy.centralNullTtl(), policy.failOpen(),
                newVersion, policy.updatedAtMillis(), policy.updatedBy(), policy.reason()));
        writeAll(all.values());
        return OptionalLong.of(newVersion);
    }

    /**
     * @return 策略文件路径（便于测试断言与人工查看）
     */
    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------ 读写

    private Map<String, StoredPolicy> readAll() {
        Map<String, StoredPolicy> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取策略文件失败：" + file, e);
        }
        for (String block : content.split(SEPARATOR)) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            StoredPolicy policy = codec.decode(trimmed.getBytes(StandardCharsets.UTF_8));
            result.put(policy.namespace(), policy);
        }
        return result;
    }

    private void writeAll(java.util.Collection<StoredPolicy> policies) {
        StringBuilder content = new StringBuilder();
        List<StoredPolicy> ordered = new ArrayList<>(policies);
        ordered.sort(Comparator.comparing(StoredPolicy::namespace));
        for (StoredPolicy policy : ordered) {
            if (!content.isEmpty()) {
                content.append(SEPARATOR);
            }
            content.append(new String(codec.encode(policy), StandardCharsets.UTF_8).stripTrailing());
        }
        content.append('\n');
        try {
            Path temp = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入策略文件失败：" + file, e);
        }
    }
}
