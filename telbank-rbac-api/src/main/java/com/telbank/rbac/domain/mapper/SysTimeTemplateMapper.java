package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysTimeTemplate;

public interface SysTimeTemplateMapper extends RbacMapper<SysTimeTemplate> {

    default Optional<SysTimeTemplate> findByName(String name) {
        return Optional.ofNullable(selectOne(Wrappers.<SysTimeTemplate>lambdaQuery()
                .eq(SysTimeTemplate::getName, name)
                .last("LIMIT 1")));
    }

    default boolean existsByName(String name) {
        return selectCount(Wrappers.<SysTimeTemplate>lambdaQuery()
                .eq(SysTimeTemplate::getName, name)) > 0;
    }

    default boolean existsByNameAndIdNot(String name, Long id) {
        return selectCount(Wrappers.<SysTimeTemplate>lambdaQuery()
                .eq(SysTimeTemplate::getName, name)
                .ne(SysTimeTemplate::getId, id)) > 0;
    }

    /**
     * 模板列表：按 id 倒序；status 为空时返回全部。
     */
    default List<SysTimeTemplate> findAllOrderByIdDesc(Integer status) {
        return selectList(Wrappers.<SysTimeTemplate>lambdaQuery()
                .eq(status != null, SysTimeTemplate::getStatus, status)
                .orderByDesc(SysTimeTemplate::getId));
    }
}
