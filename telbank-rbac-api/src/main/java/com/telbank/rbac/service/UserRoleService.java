package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.repository.SysRoleRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleCreateRequest;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleVO;

/**
 * 用户-角色分配（契约 §3.6）。
 *
 * <p>落库口径按契约 §9.1：同一 {@code (system_id, external_user_id, role_id)} 关系已存在时
 * 更新原行的时效，不新增行。</p>
 */
@Service
public class UserRoleService {

    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final SysSystemRepository systemRepository;
    private final ChangeNotifyService changeNotifyService;
    private final SystemScopeGuard systemScopeGuard;

    public UserRoleService(SysUserRoleRepository userRoleRepository,
                           SysRoleRepository roleRepository,
                           SysSystemRepository systemRepository,
                           ChangeNotifyService changeNotifyService,
                           SystemScopeGuard systemScopeGuard) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.systemRepository = systemRepository;
        this.changeNotifyService = changeNotifyService;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public List<UserRoleVO> list(Long systemId, String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            throw ApiException.badRequest("externalUserId is required");
        }
        systemId = systemScopeGuard.resolveQuerySystemId(systemId);
        List<SysUserRole> relations =
                userRoleRepository.findBySystemIdAndExternalUserIdOrderByIdAsc(systemId, externalUserId.trim());
        Map<Long, SysRole> roles = loadRoles(relations);
        List<UserRoleVO> result = new ArrayList<>(relations.size());
        for (SysUserRole relation : relations) {
            SysRole role = roles.get(relation.getRoleId());
            result.add(new UserRoleVO(relation.getId(), relation.getSystemId(), relation.getExternalUserId(),
                    relation.getRoleId(), role == null ? null : role.getCode(),
                    role == null ? null : role.getName(),
                    TimeUtils.toInstant(relation.getValidFrom()),
                    TimeUtils.toInstant(relation.getValidUntil()),
                    relation.getStatus(), relation.getGrantReason(), relation.getSourceApprovalId()));
        }
        return result;
    }

    @Transactional
    public UserRoleVO assign(UserRoleCreateRequest request) {
        systemScopeGuard.requireAccess(request.systemId());
        if (!systemRepository.existsById(request.systemId())) {
            throw ApiException.notFound("system not found: " + request.systemId());
        }
        SysRole role = roleRepository.findByIdAndSystemId(request.roleId(), request.systemId())
                .orElseThrow(() -> ApiException.badRequest(
                        "role " + request.roleId() + " does not belong to system " + request.systemId()));
        RolePermissionService.validateValidity(request.validFrom(), request.validUntil());
        String externalUserId = request.externalUserId().trim();
        SysUserRole relation = userRoleRepository
                .findBySystemIdAndExternalUserIdAndRoleId(request.systemId(), externalUserId, request.roleId())
                .orElseGet(SysUserRole::new);
        relation.setSystemId(request.systemId());
        relation.setExternalUserId(externalUserId);
        relation.setRoleId(request.roleId());
        relation.setValidFrom(TimeUtils.toUtcLocalDateTime(request.validFrom()));
        relation.setValidUntil(TimeUtils.toUtcLocalDateTime(request.validUntil()));
        relation.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
        relation.setGrantReason(request.grantReason());
        relation.setSourceApprovalId(null);
        relation.setStatus(RbacConstants.STATUS_ENABLED);
        SysUserRole saved = userRoleRepository.save(relation);
        changeNotifyService.publishAfterCommit(saved.getSystemId(), RbacConstants.CHANGE_TYPE_PERMISSION);
        return new UserRoleVO(saved.getId(), saved.getSystemId(), saved.getExternalUserId(), saved.getRoleId(),
                role.getCode(), role.getName(),
                TimeUtils.toInstant(saved.getValidFrom()), TimeUtils.toInstant(saved.getValidUntil()),
                saved.getStatus(), saved.getGrantReason(), saved.getSourceApprovalId());
    }

    /**
     * DELETE 为撤销（status=0）。
     */
    @Transactional
    public void revoke(Long id) {
        SysUserRole relation = userRoleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("user role not found: " + id));
        systemScopeGuard.requireAccess(relation.getSystemId());
        relation.setStatus(RbacConstants.STATUS_DISABLED);
        userRoleRepository.save(relation);
        changeNotifyService.publishAfterCommit(relation.getSystemId(), RbacConstants.CHANGE_TYPE_PERMISSION);
    }

    private Map<Long, SysRole> loadRoles(List<SysUserRole> relations) {
        Map<Long, SysRole> roles = new HashMap<>();
        List<Long> roleIds = relations.stream().map(SysUserRole::getRoleId).distinct().toList();
        if (!roleIds.isEmpty()) {
            for (SysRole role : roleRepository.findAllById(roleIds)) {
                roles.put(role.getId(), role);
            }
        }
        return roles;
    }
}
