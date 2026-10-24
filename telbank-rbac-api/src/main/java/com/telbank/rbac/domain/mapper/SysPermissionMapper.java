package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysPermission;

public interface SysPermissionMapper extends RbacMapper<SysPermission> {

    default List<SysPermission> findAllBySystemIdOrderBySortAscIdAsc(Long systemId) {
        return selectList(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId)
                .orderByAsc(SysPermission::getSort).orderByAsc(SysPermission::getId));
    }

    default List<SysPermission> findAllBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status) {
        return selectList(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId).eq(SysPermission::getStatus, status)
                .orderByAsc(SysPermission::getSort).orderByAsc(SysPermission::getId));
    }

    default Optional<SysPermission> findByIdAndSystemId(Long id, Long systemId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getId, id).eq(SysPermission::getSystemId, systemId)
                .last("LIMIT 1")));
    }

    default Optional<SysPermission> findBySystemIdAndCode(Long systemId, String code) {
        return Optional.ofNullable(selectOne(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId).eq(SysPermission::getCode, code)
                .last("LIMIT 1")));
    }

    default boolean existsBySystemIdAndCode(Long systemId, String code) {
        return selectCount(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId).eq(SysPermission::getCode, code)) > 0;
    }

    default boolean existsBySystemIdAndCodeAndIdNot(Long systemId, String code, Long id) {
        return selectCount(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId).eq(SysPermission::getCode, code)
                .ne(SysPermission::getId, id)) > 0;
    }

    default boolean existsBySystemIdAndParentId(Long systemId, Long parentId) {
        return selectCount(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getSystemId, systemId).eq(SysPermission::getParentId, parentId)) > 0;
    }

    /** 映射表来源：method 与 api_pattern 均非空且 status=1。 */
    @Select("""
            SELECT * FROM sys_permission
            WHERE system_id = #{systemId}
              AND status = 1
              AND method IS NOT NULL AND method <> ''
              AND api_pattern IS NOT NULL AND api_pattern <> ''
            ORDER BY sort ASC, id ASC
            """)
    List<SysPermission> findMappedBySystemId(@Param("systemId") Long systemId);
}
