package com.telbank.rbac.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.web.dto.SystemDtos.SystemCreateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemUpdateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemVO;

/**
 * 系统接入管理（契约 §3.2，仅平台超管）。
 *
 * <p>POST 在事务内完成：创建业务系统 → 默认 {@code admin} 角色 → 一条通配权限 {@code *} →
 * 角色权限授权 → 给 {@code adminExternalUserId} 建 user_role → 写入 admin_* 信息。
 * GET 仅返回业务系统（排除 is_platform=1 的平台系统）。PUT 可更新系统与管理员
 * （更换管理员时撤销旧管理员角色并授权新管理员）。</p>
 */
@Service
public class SystemService {

    /** 系统接入时自动创建的管理角色编码。 */
    public static final String DEFAULT_ADMIN_ROLE_CODE = "admin";
    public static final String DEFAULT_ADMIN_ROLE_NAME = "系统管理员";

    private final SysSystemMapper systemRepository;
    private final SysRoleMapper roleRepository;
    private final SysPermissionMapper permissionRepository;
    private final SysRolePermissionMapper rolePermissionRepository;
    private final SysUserRoleMapper userRoleRepository;

    public SystemService(SysSystemMapper systemRepository,
                         SysRoleMapper roleRepository,
                         SysPermissionMapper permissionRepository,
                         SysRolePermissionMapper rolePermissionRepository,
                         SysUserRoleMapper userRoleRepository) {
        this.systemRepository = systemRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public PageResult<SystemVO> page(String keyword, PageQuery pageQuery) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<SysSystem> mpPage = pageQuery.toMpPage();
        List<SysSystem> records = systemRepository.search(mpPage, normalizedKeyword);
        return PageResult.of(records.stream().map(SystemService::toVO).toList(),
                mpPage.getTotal(), pageQuery);
    }

    /**
     * 系统接入：创建系统 + 默认 admin 角色 + 通配权限 {@code *} + 授权 + 管理员 user_role。
     */
    @Transactional
    public SystemVO create(SystemCreateRequest request) {
        String code = request.code().trim();
        if (systemRepository.existsByCode(code)) {
            throw ApiException.conflict("system code already exists: " + code);
        }
        SysSystem system = new SysSystem();
        system.setCode(code);
        system.setName(request.name().trim());
        system.setRegion(request.region().trim());
        system.setStatus(request.status() == null ? RbacConstants.STATUS_ENABLED : request.status());
        system.setSort(request.sort() == null ? 0 : request.sort());
        system.setRemark(request.remark());
        system.setIsPlatform(0);
        String adminExternalUserId = request.adminExternalUserId().trim();
        system.setAdminExternalUserId(adminExternalUserId);
        system.setAdminName(blankToNull(request.adminName()));
        SysSystem savedSystem = systemRepository.save(system);

        SysRole adminRole = new SysRole();
        adminRole.setSystemId(savedSystem.getId());
        adminRole.setCode(DEFAULT_ADMIN_ROLE_CODE);
        adminRole.setName(DEFAULT_ADMIN_ROLE_NAME);
        adminRole.setStatus(RbacConstants.STATUS_ENABLED);
        adminRole.setSort(0);
        adminRole.setRemark("系统接入自动创建");
        SysRole savedRole = roleRepository.save(adminRole);

        SysPermission wildcardPermission = new SysPermission();
        wildcardPermission.setSystemId(savedSystem.getId());
        wildcardPermission.setParentId(0L);
        wildcardPermission.setType(2);
        wildcardPermission.setCode(RbacConstants.PERM_WILDCARD);
        wildcardPermission.setName("全部权限（通配）");
        wildcardPermission.setSort(100);
        wildcardPermission.setStatus(RbacConstants.STATUS_ENABLED);
        wildcardPermission.setIsPublic(0);
        SysPermission savedPermission = permissionRepository.save(wildcardPermission);

        SysRolePermission grant = new SysRolePermission();
        grant.setSystemId(savedSystem.getId());
        grant.setRoleId(savedRole.getId());
        grant.setPermissionId(savedPermission.getId());
        grant.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
        grant.setGrantReason("系统管理员通配权限");
        grant.setStatus(RbacConstants.STATUS_ENABLED);
        rolePermissionRepository.save(grant);

        SysUserRole userRole = new SysUserRole();
        userRole.setSystemId(savedSystem.getId());
        userRole.setExternalUserId(adminExternalUserId);
        userRole.setRoleId(savedRole.getId());
        userRole.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
        userRole.setGrantReason("系统管理员");
        userRole.setStatus(RbacConstants.STATUS_ENABLED);
        userRoleRepository.save(userRole);
        return toVO(reload(savedSystem));
    }

    /**
     * 更新系统信息与管理员；更换管理员时撤销旧管理员授权并授权新管理员。
     */
    @Transactional
    public SystemVO update(Long id, SystemUpdateRequest request) {
        SysSystem system = requireBusinessSystem(id);
        system.setName(request.name().trim());
        system.setRegion(request.region().trim());
        if (request.status() != null) {
            system.setStatus(request.status());
        }
        if (request.sort() != null) {
            system.setSort(request.sort());
        }
        if (request.remark() != null) {
            system.setRemark(request.remark());
        }
        if (request.adminExternalUserId() != null && !request.adminExternalUserId().isBlank()) {
            changeAdministrator(system, request.adminExternalUserId().trim());
        }
        if (request.adminName() != null) {
            system.setAdminName(blankToNull(request.adminName()));
        }
        return toVO(reload(systemRepository.save(system)));
    }

    private void changeAdministrator(SysSystem system, String newAdminExternalUserId) {
        String oldAdmin = system.getAdminExternalUserId();
        if (newAdminExternalUserId.equals(oldAdmin)) {
            return;
        }
        SysRole adminRole = roleRepository
                .findBySystemIdAndCode(system.getId(), DEFAULT_ADMIN_ROLE_CODE)
                .orElseThrow(() -> ApiException.badRequest(
                        "default admin role not found for system: " + system.getId()));
        if (oldAdmin != null && !oldAdmin.isBlank()) {
            userRoleRepository.findBySystemIdAndExternalUserIdAndRoleId(
                            system.getId(), oldAdmin, adminRole.getId())
                    .ifPresent(oldRelation -> {
                        oldRelation.setStatus(RbacConstants.STATUS_DISABLED);
                        userRoleRepository.save(oldRelation);
                    });
        }
        SysUserRole relation = userRoleRepository
                .findBySystemIdAndExternalUserIdAndRoleId(
                        system.getId(), newAdminExternalUserId, adminRole.getId())
                .orElseGet(SysUserRole::new);
        relation.setSystemId(system.getId());
        relation.setExternalUserId(newAdminExternalUserId);
        relation.setRoleId(adminRole.getId());
        relation.setValidFrom(null);
        relation.setValidUntil(null);
        relation.setAutoRevoke(RbacConstants.AUTO_REVOKE_YES);
        relation.setGrantReason("系统管理员");
        relation.setSourceApprovalId(null);
        relation.setStatus(RbacConstants.STATUS_ENABLED);
        userRoleRepository.save(relation);
        system.setAdminExternalUserId(newAdminExternalUserId);
    }

    @Transactional(readOnly = true)
    public SysSystem requireBusinessSystem(Long id) {
        SysSystem system = systemRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("system not found: " + id));
        if (system.getIsPlatform() != null && system.getIsPlatform() == 1) {
            throw ApiException.notFound("system not found: " + id);
        }
        return system;
    }

    @Transactional(readOnly = true)
    public SysSystem requireByCode(String code) {
        return systemRepository.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("system not found: " + code));
    }

    public static SystemVO toVO(SysSystem system) {
        return new SystemVO(system.getId(), system.getCode(), system.getName(), system.getRegion(),
                system.getStatus(), system.getSort(), system.getRemark(), system.getIsPlatform(),
                system.getAdminExternalUserId(), system.getAdminName(),
                TimeUtils.toInstant(system.getCreatedAt()), TimeUtils.toInstant(system.getUpdatedAt()));
    }

    /** 重新读取，拿到 DB 维护的 created_at/updated_at。 */
    private SysSystem reload(SysSystem system) {
        if (system == null || system.getId() == null) {
            return system;
        }
        SysSystem persisted = systemRepository.selectById(system.getId());
        return persisted == null ? system : persisted;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
