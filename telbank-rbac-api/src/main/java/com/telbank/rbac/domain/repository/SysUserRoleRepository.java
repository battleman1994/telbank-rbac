package com.telbank.rbac.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysUserRole;

public interface SysUserRoleRepository extends JpaRepository<SysUserRole, Long> {

    Optional<SysUserRole> findBySystemIdAndExternalUserIdAndRoleId(Long systemId, String externalUserId, Long roleId);

    List<SysUserRole> findBySystemIdAndExternalUserIdOrderByIdAsc(Long systemId, String externalUserId);

    Optional<SysUserRole> findFirstBySourceApprovalIdOrderByIdAsc(String sourceApprovalId);

    boolean existsBySourceApprovalId(String sourceApprovalId);

    List<SysUserRole> findBySourceApprovalIdIsNotNullOrderByIdDesc();

    List<SysUserRole> findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
            Integer status, LocalDateTime startInclusive, LocalDateTime endInclusive);

    /**
     * 用户当前生效的权限码及其时效包络（需求 §5.3 时效过滤；含 is_public=1 的公共权限）。
     *
     * <p>返回列：{@code code VARCHAR}, {@code valid_from VARCHAR}, {@code valid_until VARCHAR}
     * （时间在 SQL 中用 DATE_FORMAT 输出为 ISO-8601 字符串，避免 JDBC 时区二次转换）。</p>
     */
    @Query(value = """
            SELECT p.code AS code,
                   CASE WHEN SUM(CASE WHEN g.valid_from IS NULL THEN 1 ELSE 0 END) > 0 THEN NULL
                        ELSE DATE_FORMAT(MIN(g.valid_from), '%Y-%m-%dT%H:%i:%s') END AS valid_from,
                   CASE WHEN SUM(CASE WHEN g.valid_until IS NULL THEN 1 ELSE 0 END) > 0 THEN NULL
                        ELSE DATE_FORMAT(MAX(g.valid_until), '%Y-%m-%dT%H:%i:%s') END AS valid_until
            FROM sys_permission p
            JOIN (
                SELECT rp.permission_id AS permission_id,
                       CASE WHEN ur.valid_from IS NULL THEN rp.valid_from
                            WHEN rp.valid_from IS NULL THEN ur.valid_from
                            ELSE GREATEST(ur.valid_from, rp.valid_from) END AS valid_from,
                       CASE WHEN ur.valid_until IS NULL THEN rp.valid_until
                            WHEN rp.valid_until IS NULL THEN ur.valid_until
                            ELSE LEAST(ur.valid_until, rp.valid_until) END AS valid_until
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
                JOIN sys_role_permission rp ON rp.role_id = r.id AND rp.status = 1
                WHERE ur.status = 1
                  AND ur.system_id = :systemId
                  AND ur.external_user_id = :externalUserId
                  AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
                  AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
                  AND (rp.valid_from IS NULL OR rp.valid_from <= NOW())
                  AND (rp.valid_until IS NULL OR rp.valid_until > NOW())
                UNION ALL
                SELECT pp.id AS permission_id, NULL AS valid_from, NULL AS valid_until
                FROM sys_permission pp
                WHERE pp.system_id = :systemId AND pp.status = 1 AND pp.is_public = 1
            ) g ON g.permission_id = p.id
            WHERE p.system_id = :systemId AND p.status = 1
            GROUP BY p.id, p.code
            ORDER BY p.code
            """, nativeQuery = true)
    List<Object[]> findEffectivePermissionValidity(@Param("systemId") Long systemId,
                                                   @Param("externalUserId") String externalUserId);

    /** 定时收回：待过期 system_id 列表（发布通知用）。 */
    @Query(value = """
            SELECT DISTINCT system_id FROM sys_user_role
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """, nativeQuery = true)
    List<Long> findExpiredSystemIds();

    /** 定时收回：valid_until<=NOW() 且 auto_revoke=1 且 status=1 置为 status=0。 */
    @Modifying
    @Query(value = """
            UPDATE sys_user_role SET status = 0, updated_at = NOW()
            WHERE valid_until IS NOT NULL AND valid_until <= NOW() AND auto_revoke = 1 AND status = 1
            """, nativeQuery = true)
    int revokeExpired();
}
