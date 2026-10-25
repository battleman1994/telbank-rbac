package com.telbank.rbac.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.RoleDtos.RoleCreateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleUpdateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleVO;

/**
 * 角色管理（契约 §3.3，按 system_id 隔离）。
 *
 * <p>A21：角色可绑定最多 1 个全局时间模板；未绑定模板的角色是永久角色。
 * 系统管理员角色（{@code code=admin}）不允许绑定时间模板。</p>
 */
@Service
public class RoleService {

    /** 系统管理员角色编码：默认拥有全部权限，且不能绑定时间模板。 */
    public static final String ADMIN_ROLE_CODE = "admin";

    private final SysRoleMapper roleRepository;
    private final SysSystemMapper systemRepository;
    private final SysTimeTemplateMapper templateRepository;
    private final SystemScopeGuard systemScopeGuard;

    public RoleService(SysRoleMapper roleRepository, SysSystemMapper systemRepository,
                       SysTimeTemplateMapper templateRepository, SystemScopeGuard systemScopeGuard) {
        this.roleRepository = roleRepository;
        this.systemRepository = systemRepository;
        this.templateRepository = templateRepository;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public PageResult<RoleVO> page(Long systemId, String keyword, PageQuery pageQuery) {
        systemId = systemScopeGuard.resolveQuerySystemId(systemId);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<SysRole> mpPage = pageQuery.toMpPage();
        List<SysRole> records = roleRepository.search(mpPage, systemId, normalizedKeyword);
        Map<Long, String> templateNames = loadTemplateNames(records);
        return PageResult.of(records.stream().map(role -> toVO(role, templateNames)).toList(),
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
        SysTimeTemplate template = resolveBoundTemplate(code, request.timeTemplateId());
        SysRole role = new SysRole();
        role.setSystemId(request.systemId());
        role.setCode(code);
        role.setName(request.name().trim());
        role.setStatus(request.status() == null ? RbacConstants.STATUS_ENABLED : request.status());
        role.setSort(request.sort() == null ? 0 : request.sort());
        role.setRemark(request.remark());
        role.setTimeTemplateId(request.timeTemplateId());
        return toVO(roleRepository.save(role), templateNames(template));
    }

    @Transactional
    public RoleVO update(Long id, RoleUpdateRequest request) {
        SysRole role = requireById(id);
        systemScopeGuard.requireAccess(role.getSystemId());
        SysTimeTemplate template = resolveBoundTemplate(role.getCode(), request.timeTemplateId());
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
        // timeTemplateId 为 null 表示解绑，角色变为永久角色。
        role.setTimeTemplateId(request.timeTemplateId());
        return toVO(roleRepository.save(role), templateNames(template));
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

    /**
     * 校验角色绑定模板：null=永久角色；非空必须存在且启用；admin 角色必须为 null。
     */
    private SysTimeTemplate resolveBoundTemplate(String roleCode, Long timeTemplateId) {
        if (timeTemplateId == null) {
            return null;
        }
        if (ADMIN_ROLE_CODE.equals(roleCode)) {
            throw ApiException.badRequest("系统管理员角色不能绑定时间模板");
        }
        SysTimeTemplate template = templateRepository.findById(timeTemplateId)
                .filter(item -> Objects.equals(item.getStatus(), RbacConstants.STATUS_ENABLED))
                .orElseThrow(() -> ApiException.badRequest("时间模板不存在或已停用"));
        return template;
    }

    private Map<Long, String> loadTemplateNames(List<SysRole> roles) {
        Set<Long> templateIds = new HashSet<>();
        for (SysRole role : roles) {
            if (role.getTimeTemplateId() != null) {
                templateIds.add(role.getTimeTemplateId());
            }
        }
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SysTimeTemplate template : templateRepository.findAllById(templateIds)) {
            names.put(template.getId(), template.getName());
        }
        return names;
    }

    private static Map<Long, String> templateNames(SysTimeTemplate template) {
        return template == null ? Map.of() : Map.of(template.getId(), template.getName());
    }

    private static RoleVO toVO(SysRole role, Map<Long, String> templateNames) {
        Long templateId = role.getTimeTemplateId();
        String templateName = templateId == null ? null : templateNames.get(templateId);
        return new RoleVO(role.getId(), role.getSystemId(), role.getCode(), role.getName(),
                role.getStatus(), role.getSort(), role.getRemark(), templateId, templateName);
    }
}
