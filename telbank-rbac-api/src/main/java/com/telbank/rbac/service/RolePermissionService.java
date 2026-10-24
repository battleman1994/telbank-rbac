package com.telbank.rbac.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.repository.SysPermissionRepository;
import com.telbank.rbac.domain.repository.SysRolePermissionRepository;
import com.telbank.rbac.domain.repository.SysRoleRepository;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionGrantRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionGrantVO;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionRevokeRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionsVO;

/**
 * 角色-权限授权（契约 §3.5）。
 *
 * <p>落库口径按契约 §9.1：同一 {@code (role_id, permission_id)} 关系已存在时更新原行的
 * {@code valid_from/valid_until/source_approval_id/grant_reason/status=1}，不新增行。</p>
 */
@Service
public class RolePermissionService {

    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final ChangeNotifyService changeNotifyService;
    private final SystemScopeGuard systemScopeGuard;

    public RolePermissionService(SysRoleRepository roleRepository,
                                 SysPermissionRepository permissionRepository,
                                 SysRolePermissionRepository rolePermissionRepository,
                                 ChangeNotifyService changeNotifyService,
                                 SystemScopeGuard systemScopeGuard) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.changeNotifyService = changeNotifyService;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public RolePermissionsVO grants(Long roleId) {
        SysRole role = requireRole(roleId);
        systemScopeGuard.requireAccess(role.getSystemId());
        List<SysRolePermission> relations = rolePermissionRepository.findByRoleIdOrderByPermissionIdAsc(roleId);
        Map<Long, SysPermission> permissions = new HashMap<>();
        List<Long> permissionIds = relations.stream().map(SysRolePermission::getPermissionId).toList();
        if (!permissionIds.isEmpty()) {
            for (SysPermission permission : permissionRepository.findAllById(permissionIds)) {
                permissions.put(permission.getId(), permission);
            }
        }
        List<RolePermissionGrantVO> grants = new ArrayList<>(relations.size());
        for (SysRolePermission relation : relations) {
            SysPermission permission = permissions.get(relation.getPermissionId());
            grants.add(new RolePermissionGrantVO(relation.getPermissionId(),
                    permission == null ? null : permission.getCode(),
                    TimeUtils.toInstant(relation.getValidFrom()),
                    TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus()));
        }
        return new RolePermissionsVO(role.getId(), grants);
    }

    @Transactional
    public RolePermissionsVO grant(Long roleId, RolePermissionGrantRequest request) {
        SysRole role = requireRole(roleId);
        systemScopeGuard.requireAccess(role.getSystemId());
        validateValidity(request.validFrom(), request.validUntil());
        List<SysRolePermission> toSave = new ArrayList<>(request.permissionIds().size());
        for (Long permissionId : request.permissionIds()) {
            if (permissionId == null) {
                throw ApiException.badRequest("permissionIds must not contain null");
            }
            permissionRepository.findByIdAndSystemId(permissionId, role.getSystemId())
                    .orElseThrow(() -> ApiException.badRequest(
                            "permission " + permissionId + " does not belong to system " + role.getSystemId()));
            SysRolePermission relation = rolePermissionRepository
                    .findByRoleIdAndPermissionId(roleId, permissionId)
                    .orElseGet(SysRolePermission::new);
            relation.setSystemId(role.getSystemId());
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            relation.setValidFrom(TimeUtils.toUtcLocalDateTime(request.validFrom()));
            relation.setValidUntil(TimeUtils.toUtcLocalDateTime(request.validUntil()));
            relation.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
            relation.setGrantReason(request.grantReason());
            relation.setSourceApprovalId(null);
            relation.setStatus(RbacConstants.STATUS_ENABLED);
            toSave.add(relation);
        }
        rolePermissionRepository.saveAll(toSave);
        changeNotifyService.publishAfterCommit(role.getSystemId(), RbacConstants.CHANGE_TYPE_PERMISSION);
        return grants(roleId);
    }

    @Transactional
    public RolePermissionsVO revoke(Long roleId, RolePermissionRevokeRequest request) {
        SysRole role = requireRole(roleId);
        systemScopeGuard.requireAccess(role.getSystemId());
        for (Long permissionId : request.permissionIds()) {
            SysRolePermission relation = rolePermissionRepository
                    .findByRoleIdAndPermissionId(roleId, permissionId)
                    .orElseThrow(() -> ApiException.notFound(
                            "grant not found for role " + roleId + " and permission " + permissionId));
            relation.setStatus(RbacConstants.STATUS_DISABLED);
            rolePermissionRepository.save(relation);
        }
        changeNotifyService.publishAfterCommit(role.getSystemId(), RbacConstants.CHANGE_TYPE_PERMISSION);
        return grants(roleId);
    }

    private SysRole requireRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> ApiException.notFound("role not found: " + roleId));
    }

    static void validateValidity(Instant validFrom, Instant validUntil) {
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw ApiException.badRequest("validUntil must be after validFrom");
        }
    }

    static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
