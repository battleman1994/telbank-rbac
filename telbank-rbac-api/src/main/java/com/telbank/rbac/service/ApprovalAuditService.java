package com.telbank.rbac.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantItemVO;

/**
 * 审批授权记录 / 即将到期授权查询（契约 §3.10）。
 *
 * <p>合并 {@code sys_user_role} 与 {@code sys_role_permission} 中 {@code source_approval_id}
 * 非空的记录；kind 区分 {@code USER_ROLE} / {@code ROLE_PERMISSION}。</p>
 */
@Service
public class ApprovalAuditService {

    private static final String KIND_USER_ROLE = "USER_ROLE";
    private static final String KIND_ROLE_PERMISSION = "ROLE_PERMISSION";
    private static final int DEFAULT_WITHIN_DAYS = 7;
    private static final int MAX_WITHIN_DAYS = 365;

    private final SysUserRoleMapper userRoleRepository;
    private final SysRolePermissionMapper rolePermissionRepository;
    private final SystemScopeGuard systemScopeGuard;

    public ApprovalAuditService(SysUserRoleMapper userRoleRepository,
                                SysRolePermissionMapper rolePermissionRepository,
                                SystemScopeGuard systemScopeGuard) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public PageResult<ApprovalGrantItemVO> approvalGrants(Long systemId, String externalUserId,
                                                          PageQuery pageQuery) {
        systemId = systemScopeGuard.resolveQuerySystemId(systemId);
        List<ApprovalGrantItemVO> items = new ArrayList<>();
        for (SysUserRole relation : userRoleRepository.findBySourceApprovalIdIsNotNullOrderByIdDesc()) {
            if (systemId != null && !systemId.equals(relation.getSystemId())) {
                continue;
            }
            if (externalUserId != null && !externalUserId.isBlank()
                    && !externalUserId.trim().equals(relation.getExternalUserId())) {
                continue;
            }
            items.add(new ApprovalGrantItemVO(KIND_USER_ROLE, relation.getSystemId(),
                    relation.getExternalUserId(), relation.getRoleId(), null,
                    relation.getSourceApprovalId(), relation.getGrantReason(),
                    TimeUtils.toInstant(relation.getValidFrom()), TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus(), TimeUtils.toInstant(relation.getCreatedAt())));
        }
        for (SysRolePermission relation
                : rolePermissionRepository.findBySourceApprovalIdIsNotNullOrderByIdDesc()) {
            if (systemId != null && !systemId.equals(relation.getSystemId())) {
                continue;
            }
            if (externalUserId != null && !externalUserId.isBlank()) {
                // sys_role_permission 没有 external_user_id，只能按系统过滤
                continue;
            }
            items.add(new ApprovalGrantItemVO(KIND_ROLE_PERMISSION, relation.getSystemId(),
                    null, relation.getRoleId(), relation.getPermissionId(),
                    relation.getSourceApprovalId(), relation.getGrantReason(),
                    TimeUtils.toInstant(relation.getValidFrom()), TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus(), TimeUtils.toInstant(relation.getCreatedAt())));
        }
        items.sort(Comparator.comparing(ApprovalGrantItemVO::grantedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int total = items.size();
        int fromIndex = Math.min(pageQuery.offset(), total);
        int toIndex = Math.min(fromIndex + pageQuery.size(), total);
        return PageResult.of(new ArrayList<>(items.subList(fromIndex, toIndex)), total, pageQuery);
    }

    /**
     * 即将到期的授权：{@code valid_until BETWEEN NOW() AND NOW()+withinDays} 且 {@code status=1}，
     * 按 valid_until 升序。
     */
    @Transactional(readOnly = true)
    public List<ApprovalGrantItemVO> expiringGrants(Integer withinDays, Long systemId) {
        systemId = systemScopeGuard.resolveQuerySystemId(systemId);
        int days = withinDays == null || withinDays <= 0 ? DEFAULT_WITHIN_DAYS
                : Math.min(withinDays, MAX_WITHIN_DAYS);
        LocalDateTime now = TimeUtils.nowUtc();
        LocalDateTime end = now.plusDays(days);
        List<ApprovalGrantItemVO> items = new ArrayList<>();
        for (SysUserRole relation : userRoleRepository
                .findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
                        RbacConstants.STATUS_ENABLED, now, end)) {
            if (systemId != null && !systemId.equals(relation.getSystemId())) {
                continue;
            }
            items.add(new ApprovalGrantItemVO(KIND_USER_ROLE, relation.getSystemId(),
                    relation.getExternalUserId(), relation.getRoleId(), null,
                    relation.getSourceApprovalId(), relation.getGrantReason(),
                    TimeUtils.toInstant(relation.getValidFrom()), TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus(), TimeUtils.toInstant(relation.getCreatedAt())));
        }
        for (SysRolePermission relation : rolePermissionRepository
                .findByStatusAndValidUntilIsNotNullAndValidUntilBetweenOrderByValidUntilAsc(
                        RbacConstants.STATUS_ENABLED, now, end)) {
            if (systemId != null && !systemId.equals(relation.getSystemId())) {
                continue;
            }
            items.add(new ApprovalGrantItemVO(KIND_ROLE_PERMISSION, relation.getSystemId(),
                    null, relation.getRoleId(), relation.getPermissionId(),
                    relation.getSourceApprovalId(), relation.getGrantReason(),
                    TimeUtils.toInstant(relation.getValidFrom()), TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus(), TimeUtils.toInstant(relation.getCreatedAt())));
        }
        items.sort(Comparator.comparing(ApprovalGrantItemVO::validUntil,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }
}
