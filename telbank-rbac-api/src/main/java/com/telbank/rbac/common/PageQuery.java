package com.telbank.rbac.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 管理端分页参数归一化（契约 §1.3：请求 {@code ?page=1&size=20}，缺省 page=1、size=20）。
 *
 * @param page 页码，从 1 开始
 * @param size 每页条数
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public static PageQuery of(Integer page, Integer size) {
        int normalizedPage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int normalizedSize = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageQuery(normalizedPage, normalizedSize);
    }

    public int offset() {
        return (page - 1) * size;
    }

    /**
     * 适配 MyBatis-Plus 分页对象（页码从 1 开始，与前端契约一致）。
     */
    public <T> Page<T> toMpPage() {
        return new Page<>(page, size);
    }
}
