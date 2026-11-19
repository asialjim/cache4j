package com.asialjim.cache4j.core.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 统一日志门面：固定格式 {@code [主题]-子过程-具体信息}。
 *
 * <h2>格式</h2>
 * <pre>{@code
 * [user:detail]-对账-清除失效副本 3 条，中央版本已推进
 * [user:detail]-读-中央读取失败，按未命中降级，原因=CENTRAL_UNAVAILABLE
 * [cache4j]-装配-装配 2 个缓存：user:detail(STATIC), product:price(DYNAMIC)
 * }</pre>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li><b>主题</b>：缓存相关日志用 namespace；全局/装配类用 {@code cache4j}；</li>
 *   <li><b>子过程</b>：使用固定词表（装配 / 读 / 写 / 失效 / 清空 / 对账 / 回填 / 单飞 /
 *       热key / 空值缓存 / 编解码 / key编码 / 降级 / 关闭），避免同一处出现两种写法；</li>
 *   <li><b>具体信息</b>：不打印原始 key（可能含手机号、证件号），一律用 {@code CacheKey#masked()}；</li>
 *   <li>参数化日志用 {@link Supplier} 延迟拼接：关闭对应级别时不产生字符串构造开销；</li>
 *   <li>基础设施故障的 WARN 使用 {@link #warnThrottled} 限流，避免故障期刷爆日志。</li>
 * </ul>
 *
 * <h2>级别</h2>
 * <table border="1">
 *   <caption>级别约定</caption>
 *   <tr><th>级别</th><th>用于</th></tr>
 *   <tr><td>ERROR</td><td>数据/编程错误与启动失败：编解码失败、key 编码失败、配置非法</td></tr>
 *   <tr><td>WARN</td><td>可降级的基础设施故障：中央读写失效失败、对账失败、热 key 上报失败、
 *       loader 失败、缺少 dynamic.global 配置</td></tr>
 *   <tr><td>INFO</td><td>生命周期：装配、关闭、降级开关状态</td></tr>
 *   <tr><td>DEBUG</td><td>状态变化：对账轮次结果、升降热、回填丢弃、上报批次</td></tr>
 * </table>
 *
 * <p><b>普通缓存命中不打日志</b> —— 那是热路径，靠指标统计即可。</p>
 *
 * @author Asial Jim
 */
public final class CacheLogger {

    /**
     * 默认限流窗口：同一 (主题, 子过程) 至少间隔这么久才再记一次 WARN。
     */
    private static final long DEFAULT_THROTTLE_NANOS = 30_000_000_000L;

    /**
     * 全局主题：与具体缓存无关的日志（装配、关闭等）。
     */
    public static final String GLOBAL_SUBJECT = "cache4j";

    private final Logger delegate;
    private final ConcurrentHashMap<String, Long> lastThrottledAt = new ConcurrentHashMap<>();

    private CacheLogger(Logger delegate) {
        this.delegate = delegate;
    }

    /**
     * @param type 声明日志的类
     * @return 门面
     */
    public static CacheLogger get(Class<?> type) {
        return new CacheLogger(LoggerFactory.getLogger(type));
    }

    /**
     * @return DEBUG 是否开启（用于跳过昂贵的详情构造）
     */
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    /**
     * @return WARN 是否开启
     */
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    /**
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     */
    public void error(String subject, String process, String detail) {
        if (delegate.isErrorEnabled()) {
            delegate.error(format(subject, process, detail));
        }
    }

    /**
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     * @param cause   原因
     */
    public void error(String subject, String process, String detail, Throwable cause) {
        if (delegate.isErrorEnabled()) {
            delegate.error(format(subject, process, detail), cause);
        }
    }

    /**
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     */
    public void warn(String subject, String process, String detail) {
        if (delegate.isWarnEnabled()) {
            delegate.warn(format(subject, process, detail));
        }
    }

    /**
     * 限流 WARN：默认 30s 内同一 (主题, 子过程) 只记一次。
     *
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息（延迟构造）
     */
    public void warnThrottled(String subject, String process, Supplier<String> detail) {
        warnThrottled(subject, process, detail, DEFAULT_THROTTLE_NANOS);
    }

    /**
     * 限流 WARN。
     *
     * @param subject     主题
     * @param process     子过程
     * @param detail      具体信息（延迟构造）
     * @param windowNanos 限流窗口（纳秒）
     */
    public void warnThrottled(String subject, String process, Supplier<String> detail, long windowNanos) {
        if (!delegate.isWarnEnabled()) {
            return;
        }
        String throttleKey = nullSafe(subject) + '|' + nullSafe(process);
        long now = System.nanoTime();
        Long last = lastThrottledAt.get(throttleKey);
        if (last != null && now - last < windowNanos) {
            return;
        }
        lastThrottledAt.put(throttleKey, now);
        delegate.warn(format(subject, process, detail.get()));
    }

    /**
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     */
    public void info(String subject, String process, String detail) {
        if (delegate.isInfoEnabled()) {
            delegate.info(format(subject, process, detail));
        }
    }

    /**
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     */
    public void debug(String subject, String process, String detail) {
        if (delegate.isDebugEnabled()) {
            delegate.debug(format(subject, process, detail));
        }
    }

    /**
     * DEBUG 的延迟构造版本：级别关闭时不构造字符串。
     *
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息（延迟构造）
     */
    public void debug(String subject, String process, Supplier<String> detail) {
        if (delegate.isDebugEnabled()) {
            delegate.debug(format(subject, process, detail.get()));
        }
    }

    /**
     * 组装统一格式 {@code [主题]-子过程-具体信息}。
     *
     * @param subject 主题
     * @param process 子过程
     * @param detail  具体信息
     * @return 完整日志文本
     */
    public static String format(String subject, String process, String detail) {
        return '[' + nullSafe(subject) + "]-" + nullSafe(process) + '-' + nullSafe(detail);
    }

    private static String nullSafe(String value) {
        return value == null ? "-" : value;
    }
}
