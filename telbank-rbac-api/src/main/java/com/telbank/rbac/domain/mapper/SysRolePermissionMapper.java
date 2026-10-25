package com.telbank.rbac.domain.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 角色当前有效的权限行：授权启用（status=1）且在时效内、权限启用且属于当前系统。
     *
     * <p>用于申请人「可申请选项」的权限预览，批量查询避免 N+1；调用方需保证 roleIds 非空。</p>
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
              AND (rp.valid_from IS NULL OR rp.valid_from &lt;= NOW())
              AND (rp.valid_until IS NULL OR rp.valid_until &gt; NOW())
            ORDER BY rp.role_id, p.sort, p.id
            </script>
            """)
    List<AccessRolePermissionRow> findActivePermissionRows(@Param("systemId") Long systemId,
                                                           @Param("roleIds") List<Long> roleIds);

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
