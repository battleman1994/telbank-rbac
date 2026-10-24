package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.repository.SysRolePermissionRepository;
import com.telbank.rbac.domain.repository.SysRoleRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

/**
 * 审批回调授权（契约 §5）。
 *
 * <ul>
 *   <li>按 {@code approvalId} 幂等：已处理过直接返回 {@code idempotent=true}；</li>
 *   <li>仅支持 {@code grantType=role}；{@code grantType=permission} 返回 400（数据模型无 user_permission 表）；</li>
 *   <li>{@code permissionCodes} 仅作审批内容审计：必须全部是角色已授权权限码；</li>
 *   <li>落库口径契约 §9.1：同用户+角色关系已存在则更新时效，不新增行。</li>
 * </ul>
 */
@Service
public class ApprovalGrantService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGrantService.class);

    private static final String GRANT_TYPE_ROLE = "role";
    private static final String GRANT_TYPE_PERMISSION = "permission";

    private final SysSystemRepository systemRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final ChangeNotifyService changeNotifyService;

    public ApprovalGrantService(SysSystemRepository systemRepository,
                                SysRoleRepository roleRepository,
                                SysUserRoleRepository userRoleRepository,
                                SysRolePermissionRepository rolePermissionRepository,
                                ChangeNotifyService changeNotifyService) {
        this.systemRepository = systemRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.changeNotifyService = changeNotifyService;
    }

    @Transactional
    public ApprovalGrantResponse grant(ApprovalGrantRequest request) {
        validateGrantType(request.grantType());
        String approvalId = request.approvalId().trim();

        // 1. 幂等：同一 approvalId 已成功处理过
        SysUserRole alreadyGranted = userRoleRepository
                .findFirstBySourceApprovalIdOrderByIdAsc(approvalId)
                .orElse(null);
        if (alreadyGranted != null) {
            log.info("approval grant is idempotent: approvalId={} grantId={}", approvalId, alreadyGranted.getId());
            return new ApprovalGrantResponse(true, alreadyGranted.getId(), true);
        }

        // 2. 系统与角色校验
        String systemCode = request.systemCode().trim();
        SysSystem system = systemRepository.findByCode(systemCode)
                .orElseThrow(() -> ApiException.badRequest("system not found: " + systemCode));
        if (request.roleId() == null) {
            throw ApiException.badRequest("roleId is required when grantType=role");
        }
        SysRole role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> ApiException.badRequest("role not found: " + request.roleId()));
        if (!role.getSystemId().equals(system.getId())) {
            throw ApiException.badRequest(
                    "role " + role.getId() + " does not belong to system " + systemCode);
        }
        RolePermissionService.validateValidity(request.validFrom(), request.validUntil());

        // 3. permissionCodes 审计：必须是该角色已授权的权限码，否则 400（契约 §5.4）
        validatePermissionCodes(request.permissionCodes(), role.getId());

        // 4. 落库口径 §9.1：同关系更新原行，不新增行
        String externalUserId = request.externalUserId().trim();
        SysUserRole relation = userRoleRepository
                .findBySystemIdAndExternalUserIdAndRoleId(system.getId(), externalUserId, role.getId())
                .orElseGet(SysUserRole::new);
        relation.setSystemId(system.getId());
        relation.setExternalUserId(externalUserId);
        relation.setRoleId(role.getId());
        relation.setValidFrom(TimeUtils.toUtcLocalDateTime(request.validFrom()));
        relation.setValidUntil(TimeUtils.toUtcLocalDateTime(request.validUntil()));
        relation.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
        relation.setGrantReason(request.reason());
        relation.setSourceApprovalId(approvalId);
        relation.setStatus(RbacConstants.STATUS_ENABLED);
        SysUserRole saved = userRoleRepository.save(relation);

        // 5. 发布 Redis 变更通知（事务提交后）
        changeNotifyService.publishAfterCommit(system.getId(), RbacConstants.CHANGE_TYPE_PERMISSION);
        log.info("approval grant applied: approvalId={} systemCode={} externalUserId={} roleId={} grantId={}",
                approvalId, systemCode, externalUserId, role.getId(), saved.getId());
        return new ApprovalGrantResponse(true, saved.getId(), false);
    }

    private void validateGrantType(String grantType) {
        if (grantType == null || grantType.isBlank()) {
            throw ApiException.badRequest("grantType is required");
        }
        String normalized = grantType.trim().toLowerCase();
        if (GRANT_TYPE_PERMISSION.equals(normalized)) {
            throw ApiException.badRequest(
                    "grantType=permission is not supported: there is no user_permission table, "
                            + "please grant permissions through a role");
        }
        if (!GRANT_TYPE_ROLE.equals(normalized)) {
            throw ApiException.badRequest("unsupported grantType: " + grantType + " (supported: role)");
        }
    }

    private void validatePermissionCodes(List<String> permissionCodes, Long roleId) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String code : permissionCodes) {
            if (code != null && !code.isBlank()) {
                requested.add(code.trim());
            }
        }
        if (requested.isEmpty()) {
            return;
        }
        Set<String> granted = new LinkedHashSet<>(rolePermissionRepository.findGrantedCodesByRoleId(roleId));
        List<String> missing = new ArrayList<>();
        for (String code : requested) {
            if (!granted.contains(code)) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest(
                    "permissionCodes are not granted to role " + roleId + ": " + missing);
        }
    }

    /**
     * 仅用于测试/诊断：审批回调可接受的 grantType。
     */
    public static Set<String> supportedGrantTypes() {
        return Set.of(GRANT_TYPE_ROLE);
    }
}
