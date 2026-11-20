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
     * @return 总页数
     */
    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) ((total + size - 1L) / size);
    }
}
