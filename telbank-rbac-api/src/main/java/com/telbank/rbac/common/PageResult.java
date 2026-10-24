package com.telbank.rbac.common;

import java.util.List;

/**
 * 分页响应体（契约 §1.3）：{@code {"items":[...],"total":123,"page":1,"size":20}}。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, PageQuery pageQuery) {
        return new PageResult<>(items, total, pageQuery.page(), pageQuery.size());
    }
}
