package com.telbank.rbac.domain.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysRolePermission;

public interface SysRolePermissionMapper extends RbacMapper<SysRolePermission> {

    default Optional<SysRolePermission> findByRoleIdAndPermissionId(Long roleId, Long permissionId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId)
                .eq(SysRolePermission::getPermissionId, permissionId)
                .last("LIMIT 1")));
    }

    default List<SysRolePermission> findByRoleIdOrderByPermissionIdAsc(Long roleId) {
        return selectList(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId)
                .orderByAsc(SysRolePermission::getPermissionId));
    }

    default Optional<SysRolePermission> findFirstBySourceApprovalIdOrderByIdAsc(String sourceApprovalId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getSourceApprovalId, sourceApprovalId)
                .orderByAsc(SysRolePermission::getId)
                .last("LIMIT 1")));
    }

    default List<SysRolePermission> findBySourceApprovalIdIsNotNullOrderByIdDesc() {
        return selectList(Wrappers.<SysRolePermission>lambdaQuery()
                .isNotNull(SysRolePermission::getSourceApprovalId)
                .orderByDesc(SysRolePermission::getId));
    }

    default List<SysRolePermission> findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
            Integer status, LocalDateTime startInclusive, LocalDateTime endInclusive) {
        return selectList(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getStatus, status)
                .isNotNull(SysRolePermission::getValidUntil)
                .between(SysRolePermission::getValidUntil, startInclusive, endInclusive)
                .orderByAsc(SysRolePermission::getValidUntil));
    }

    /** 角色已授权（status=1）的权限码，用于审批回调内容审计。 */
    @Select("""
            SELECT DISTINCT p.code FROM sys_role_permission rp
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
              AND rp.status = 1
              AND p.status = 1
            """)
    List<String> findGrantedCodesByRoleId(@Param("roleId") Long roleId);

    /** 定时收回：待过期 system_id 列表。 */
    @Select("""
            SELECT DISTINCT system_id FROM sys_role_permission
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """)
    List<Long> findExpiredSystemIds();

    /** 定时收回：valid_until<=NOW() 且 auto_revoke=1 且 status=1 置 status=0。 */
    @Update("""
            UPDATE sys_role_permission SET status = 0, updated_at = NOW()
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """)
    int revokeExpired();
}
