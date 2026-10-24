package com.telbank.rbac.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysRolePermission;

public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, Long> {

    Optional<SysRolePermission> findByRoleIdAndPermissionId(Long roleId, Long permissionId);

    List<SysRolePermission> findByRoleIdOrderByPermissionIdAsc(Long roleId);

    Optional<SysRolePermission> findFirstBySourceApprovalIdOrderByIdAsc(String sourceApprovalId);

    List<SysRolePermission> findBySourceApprovalIdIsNotNullOrderByIdDesc();

    List<SysRolePermission> findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
            Integer status, LocalDateTime startInclusive, LocalDateTime endInclusive);

    /** 角色已授权（status=1）的权限码，用于审批回调内容审计。 */
    @Query("""
            select distinct p.code from SysRolePermission rp, SysPermission p
            where p.id = rp.permissionId
              and rp.roleId = :roleId
              and rp.status = 1
              and p.status = 1
            """)
    List<String> findGrantedCodesByRoleId(@Param("roleId") Long roleId);

    /** 定时收回：待过期 system_id 列表（发布通知用）。 */
    @Query(value = """
            SELECT DISTINCT system_id FROM sys_role_permission
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """, nativeQuery = true)
    List<Long> findExpiredSystemIds();

    /** 定时收回：valid_until<=NOW() 且 auto_revoke=1 且 status=1 置为 status=0。 */
    @Modifying
    @Query(value = """
            UPDATE sys_role_permission SET status = 0, updated_at = NOW()
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """, nativeQuery = true)
    int revokeExpired();
}
