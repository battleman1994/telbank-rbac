package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.mapper.row.AccessRolePermissionRow;

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

    /** 角色已授权（status=1）的权限码，用于审批回调内容审计。 */
    @Select("""
            SELECT DISTINCT p.code FROM sys_role_permission rp
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
              AND rp.status = 1
              AND p.status = 1
            """)
    List<String> findGrantedCodesByRoleId(@Param("roleId") Long roleId);

    /**
     * 角色当前的权限行：授权与权限均启用且属于当前系统。
     *
     * <p>A21 起角色-权限不再带时效；用于申请人「可申请选项」的权限预览，批量查询避免 N+1；
     * 调用方需保证 roleIds 非空。</p>
     */
    @Select("""
            <script>
            SELECT rp.role_id AS roleId, p.id AS id, p.code AS code, p.name AS name, p.type AS type
            FROM sys_role_permission rp
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE rp.role_id IN
              <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">#{roleId}</foreach>
              AND rp.status = 1
              AND p.system_id = #{systemId}
              AND p.status = 1
            ORDER BY rp.role_id, p.sort, p.id
            </script>
            """)
    List<AccessRolePermissionRow> findActivePermissionRows(@Param("systemId") Long systemId,
                                                           @Param("roleIds") List<Long> roleIds);
}
