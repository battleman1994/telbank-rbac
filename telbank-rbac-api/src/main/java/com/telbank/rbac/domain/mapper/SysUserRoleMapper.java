package com.telbank.rbac.domain.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.row.PermissionValidityRow;

public interface SysUserRoleMapper extends RbacMapper<SysUserRole> {

    default Optional<SysUserRole> findBySystemIdAndExternalUserIdAndRoleId(
            Long systemId, String externalUserId, Long roleId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getSystemId, systemId)
                .eq(SysUserRole::getExternalUserId, externalUserId)
                .eq(SysUserRole::getRoleId, roleId)
                .last("LIMIT 1")));
    }

    default List<SysUserRole> findBySystemIdAndExternalUserIdOrderByIdAsc(
            Long systemId, String externalUserId) {
        return selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getSystemId, systemId)
                .eq(SysUserRole::getExternalUserId, externalUserId)
                .orderByAsc(SysUserRole::getId));
    }

    default Optional<SysUserRole> findFirstBySourceApprovalIdOrderByIdAsc(String sourceApprovalId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getSourceApprovalId, sourceApprovalId)
                .orderByAsc(SysUserRole::getId)
                .last("LIMIT 1")));
    }

    default boolean existsBySourceApprovalId(String sourceApprovalId) {
        return selectCount(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getSourceApprovalId, sourceApprovalId)) > 0;
    }

    default List<SysUserRole> findBySourceApprovalIdIsNotNullOrderByIdDesc() {
        return selectList(Wrappers.<SysUserRole>lambdaQuery()
                .isNotNull(SysUserRole::getSourceApprovalId)
                .orderByDesc(SysUserRole::getId));
    }

    default List<SysUserRole> findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
            Integer status, LocalDateTime startInclusive, LocalDateTime endInclusive) {
        return selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getStatus, status)
                .isNotNull(SysUserRole::getValidUntil)
                .between(SysUserRole::getValidUntil, startInclusive, endInclusive)
                .orderByAsc(SysUserRole::getValidUntil));
    }

    /**
     * 用户当前生效的权限码及其时效包络（时效过滤，含 is_public=1 公共权限）。
     * A21：角色-权限不再带时效，生效窗口直接取 {@code sys_user_role} 的窗口。
     * 时间用 DATE_FORMAT 输出 ISO-8601 字符串，避免 JDBC 时区二次转换。
     */
    @Select("""
            SELECT p.code AS code,
                   CASE WHEN SUM(CASE WHEN g.valid_from IS NULL THEN 1 ELSE 0 END) > 0 THEN NULL
                        ELSE DATE_FORMAT(MIN(g.valid_from), '%Y-%m-%dT%H:%i:%s') END AS valid_from,
                   CASE WHEN SUM(CASE WHEN g.valid_until IS NULL THEN 1 ELSE 0 END) > 0 THEN NULL
                        ELSE DATE_FORMAT(MAX(g.valid_until), '%Y-%m-%dT%H:%i:%s') END AS valid_until
            FROM sys_permission p
            JOIN (
                SELECT rp.permission_id AS permission_id,
                       ur.valid_from AS valid_from,
                       ur.valid_until AS valid_until
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
                JOIN sys_role_permission rp ON rp.role_id = r.id AND rp.status = 1
                WHERE ur.status = 1
                  AND ur.system_id = #{systemId}
                  AND ur.external_user_id = #{externalUserId}
                  AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
                  AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
                UNION ALL
                SELECT pp.id AS permission_id, NULL AS valid_from, NULL AS valid_until
                FROM sys_permission pp
                WHERE pp.system_id = #{systemId} AND pp.status = 1 AND pp.is_public = 1
            ) g ON g.permission_id = p.id
            WHERE p.system_id = #{systemId} AND p.status = 1
            GROUP BY p.id, p.code
            ORDER BY p.code
            """)
    List<PermissionValidityRow> findEffectivePermissionValidity(@Param("systemId") Long systemId,
                                                                @Param("externalUserId") String externalUserId);

    /** 用户可访问系统（由有效角色推导，DISTINCT system_id）。 */
    @Select("""
            SELECT DISTINCT ur.system_id FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
            WHERE ur.external_user_id = #{externalUserId}
              AND ur.status = 1
              AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
              AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
            """)
    List<Long> findAccessibleSystemIds(@Param("externalUserId") String externalUserId);

    /** 用户在某系统当前有效的角色 id（临时权限申请「已拥有」判断）。 */
    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.system_id = ur.system_id
            WHERE ur.system_id = #{systemId}
              AND ur.external_user_id = #{externalUserId}
              AND ur.status = 1
              AND r.status = 1
              AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
              AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
            ORDER BY ur.role_id
            """)
    List<Long> findActiveRoleIds(@Param("systemId") Long systemId,
                                 @Param("externalUserId") String externalUserId);

    /** 用户在某系统是否有有效角色（管理上下文准入）。 */
    @Select("""
            SELECT COUNT(*) FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
            WHERE ur.system_id = #{systemId}
              AND ur.external_user_id = #{externalUserId}
              AND ur.status = 1
              AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
              AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
            """)
    long countEffectiveRoles(@Param("systemId") Long systemId,
                             @Param("externalUserId") String externalUserId);

    /** 用户在指定系统内是否拥有某权限码的有效授权（数据驱动平台超管判定）。 */
    @Select("""
            SELECT COUNT(*) FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
            JOIN sys_role_permission rp ON rp.role_id = r.id AND rp.status = 1
            JOIN sys_permission p ON p.id = rp.permission_id AND p.status = 1
            WHERE p.system_id = #{systemId}
              AND p.code = #{code}
              AND ur.system_id = #{systemId}
              AND ur.external_user_id = #{externalUserId}
              AND ur.status = 1
              AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
              AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
            """)
    long countEffectivePermission(@Param("systemId") Long systemId,
                                  @Param("externalUserId") String externalUserId,
                                  @Param("code") String code);

    /** 定时收回：待过期 system_id 列表。 */
    @Select("""
            SELECT DISTINCT system_id FROM sys_user_role
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """)
    List<Long> findExpiredSystemIds();

    /** 定时收回：valid_until<=NOW() 且 auto_revoke=1 且 status=1 置 status=0。 */
    @Update("""
            UPDATE sys_user_role SET status = 0, updated_at = NOW()
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """)
    int revokeExpired();
}
