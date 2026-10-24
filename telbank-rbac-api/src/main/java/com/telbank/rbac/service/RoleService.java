package com.telbank.rbac.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.RoleDtos.RoleCreateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleUpdateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleVO;

/**
 * 角色管理（契约 §3.3，按 system_id 隔离）。
 */
@Service
public class RoleService {

    private final SysRoleMapper roleRepository;
    private final SysSystemMapper systemRepository;
    private final SystemScopeGuard systemScopeGuard;

    public RoleService(SysRoleMapper roleRepository, SysSystemMapper systemRepository,
                       SystemScopeGuard systemScopeGuard) {
        this.roleRepository = roleRepository;
        this.systemRepository = systemRepository;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public PageResult<RoleVO> page(Long systemId, String keyword, PageQuery pageQuery) {
        systemId = systemScopeGuard.resolveQuerySystemId(systemId);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<SysRole> mpPage = pageQuery.toMpPage();
        List<SysRole> records = roleRepository.search(mpPage, systemId, normalizedKeyword);
        return PageResult.of(records.stream().map(RoleService::toVO).toList(),
                mpPage.getTotal(), pageQuery);
    }

    @Transactional
    public RoleVO create(RoleCreateRequest request) {
        systemScopeGuard.requireAccess(request.systemId());
        if (!systemRepository.existsById(request.systemId())) {
            throw ApiException.notFound("system not found: " + request.systemId());
        }
        String code = request.code().trim();
        if (roleRepository.existsBySystemIdAndCode(request.systemId(), code)) {
            throw ApiException.conflict("role code already exists in system: " + code);
        }
        SysRole role = new SysRole();
        role.setSystemId(request.systemId());
        role.setCode(code);
        role.setName(request.name().trim());
        role.setStatus(request.status() == null ? RbacConstants.STATUS_ENABLED : request.status());
        role.setSort(request.sort() == null ? 0 : request.sort());
        role.setRemark(request.remark());
        return toVO(roleRepository.save(role));
    }

    @Transactional
    public RoleVO update(Long id, RoleUpdateRequest request) {
        SysRole role = requireById(id);
        systemScopeGuard.requireAccess(role.getSystemId());
        role.setName(request.name().trim());
        if (request.status() != null) {
            role.setStatus(request.status());
        }
        if (request.sort() != null) {
            role.setSort(request.sort());
        }
        if (request.remark() != null) {
            role.setRemark(request.remark());
        }
        return toVO(roleRepository.save(role));
    }

    @Transactional
    public void disable(Long id) {
        SysRole role = requireById(id);
        systemScopeGuard.requireAccess(role.getSystemId());
        role.setStatus(RbacConstants.STATUS_DISABLED);
        roleRepository.save(role);
    }

    @Transactional(readOnly = true)
    public SysRole requireById(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("role not found: " + id));
    }

    @Transactional(readOnly = true)
    public SysRole requireByIdAndSystem(Long id, Long systemId) {
        return roleRepository.findByIdAndSystemId(id, systemId)
                .orElseThrow(() -> ApiException.notFound(
                        "role " + id + " not found in system " + systemId));
    }

    public static RoleVO toVO(SysRole role) {
        return new RoleVO(role.getId(), role.getSystemId(), role.getCode(), role.getName(),
                role.getStatus(), role.getSort(), role.getRemark());
    }
}
