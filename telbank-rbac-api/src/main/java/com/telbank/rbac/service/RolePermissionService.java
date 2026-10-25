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
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
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
 *
 * <p>A18：admin 角色（code={@code admin}）默认拥有全部权限，grant/revoke 返回 400；
 * 读取授权列表保持原行为。</p>
 *
 * <p>业务用户临时权限申请（Task 5）：通配权限 {@code *} 只属于系统管理员角色，
 * 非 admin 角色手工授予 {@code *} 一律 400；admin 角色本身的 grant/revoke 仍按 A18 拒绝。</p>
 */
@Service
public class RolePermissionService {

    private final SysRoleMapper roleRepository;
    private final SysPermissionMapper permissionRepository;
    private final SysRolePermissionMapper rolePermissionRepository;
    private final ChangeNotifyService changeNotifyService;
    private final SystemScopeGuard systemScopeGuard;

    public RolePermissionService(SysRoleMapper roleRepository,
                                 SysPermissionMapper permissionRepository,
                                 SysRolePermissionMapper rolePermissionRepository,
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
        rejectAdminRoleChanges(role);
        validateValidity(request.validFrom(), request.validUntil());
        List<SysRolePermission> toSave = new ArrayList<>(request.permissionIds().size());
        for (Long permissionId : request.permissionIds()) {
            if (permissionId == null) {
                throw ApiException.badRequest("permissionIds must not contain null");
            }
            SysPermission permission = permissionRepository.findByIdAndSystemId(permissionId, role.getSystemId())
                    .orElseThrow(() -> ApiException.badRequest(
                            "permission " + permissionId + " does not belong to system " + role.getSystemId()));
            rejectWildcardGrant(permission);
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
        rejectAdminRoleChanges(role);
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

    /**
     * A18：admin 角色默认拥有全部权限（{@code *}），禁止通过角色授权接口添加/撤销授权。
     */
    private static void rejectAdminRoleChanges(SysRole role) {
        if (SystemService.DEFAULT_ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw ApiException.badRequest("系统管理员角色默认拥有全部权限，无需授权");
        }
    }

    /**
     * Task 5：非 admin 角色禁止手工授予通配权限 {@code *}，通配权限只由系统管理员角色默认拥有。
     */
    private static void rejectWildcardGrant(SysPermission permission) {
        if (RbacConstants.PERM_WILDCARD.equals(permission.getCode())) {
            throw ApiException.badRequest("通配权限 * 只能由系统管理员角色默认拥有，不能手工授权");
        }
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
