package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.SysRole;

public interface SysRoleMapper extends RbacMapper<SysRole> {

    default Optional<SysRole> findByIdAndSystemId(Long id, Long systemId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getId, id).eq(SysRole::getSystemId, systemId)
                .last("LIMIT 1")));
    }

    default Optional<SysRole> findBySystemIdAndCode(Long systemId, String code) {
        return Optional.ofNullable(selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSystemId, systemId).eq(SysRole::getCode, code)
                .last("LIMIT 1")));
    }

    default boolean existsBySystemIdAndCode(Long systemId, String code) {
        return selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSystemId, systemId).eq(SysRole::getCode, code)) > 0;
    }

    default boolean existsBySystemIdAndCodeAndIdNot(Long systemId, String code, Long id) {
        return selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSystemId, systemId).eq(SysRole::getCode, code)
                .ne(SysRole::getId, id)) > 0;
    }

    default List<SysRole> findAllBySystemIdOrderBySortAscIdAsc(Long systemId) {
        return selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSystemId, systemId)
                .orderByAsc(SysRole::getSort).orderByAsc(SysRole::getId));
    }

    default List<SysRole> findAllBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status) {
        return selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSystemId, systemId).eq(SysRole::getStatus, status)
                .orderByAsc(SysRole::getSort).orderByAsc(SysRole::getId));
    }

    @Select("""
            <script>
            SELECT * FROM sys_role
            WHERE system_id = #{systemId}
            <if test="keyword != null and keyword != ''">
              AND (LOWER(code) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                   OR LOWER(name) LIKE CONCAT('%', LOWER(#{keyword}), '%'))
            </if>
            ORDER BY sort ASC, id ASC
            </script>
            """)
    List<SysRole> search(Page<SysRole> page, @Param("systemId") Long systemId,
                         @Param("keyword") String keyword);
}
