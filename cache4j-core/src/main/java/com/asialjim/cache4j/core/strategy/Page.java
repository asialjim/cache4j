package com.asialjim.cache4j.core.strategy;

import java.util.List;

/**
 * 简单分页结果（运维"先列表再进详情"的列表页）。
 *
 * @param items 当前页
 * @param page  页码（从 0 开始）
 * @param size  页大小
 * @param total 总条数
 * @param <T>   元素类型
 * @author Asial Jim
 */
public record Page<T>(List<T> items, int page, int size, long total) {

    /**
     * 紧凑构造器：把 items 变成不可变副本。
     */
    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 便利构造：对一批内存记录做模糊过滤 + 分页。
     *
     * <p><strong>只给"记录数有界"的实现用</strong>（例如未启用数据源时的声明列表、
     * 测试替身）。真正的数据源必须自己在存储侧过滤与分页——把全量拉回来再切页在
     * namespace 上千时等于每次翻页都做一次全量传输。</p>
     *
     * @param all     全部候选
     * @param keyword 模糊关键字（匹配命名空间，可空）
     * @param page    页码（0 起）
     * @param size    页大小
     * @param <T>     元素类型
     * @return 分页结果
     */
    public static <T extends StoredPolicy> Page<T> of(List<T> all, String keyword, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        String lowered = keyword == null ? "" : keyword.trim().toLowerCase();
        List<T> matched = new java.util.ArrayList<>(all.size());
        for (T item : all) {
            if (lowered.isEmpty()
                    || item.namespace().toLowerCase().contains(lowered)
                    || (item.description() != null && item.description().toLowerCase().contains(lowered))) {
                matched.add(item);
            }
        }
        int from = Math.min(matched.size(), safePage * safeSize);
        int to = Math.min(matched.size(), from + safeSize);
        return new Page<>(matched.subList(from, to), safePage, safeSize, matched.size());
    }

    /**
     * @return 总页数
     */
    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) ((total + size - 1L) / size);
    }
}
