package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.OpenApiDtos.UserSystemItem;

/**
 * 用户可访问系统（系统隔离模型）：由用户在各系统中的「有效角色」推导，不再有用户-系统开通表。
 *
 * <p>平台系统（is_platform=1）不参与业务系统选择；有效角色 = {@code sys_user_role.status=1} 且时效内（{@code valid_from/valid_until}），
 * 角色 {@code status=1}，系统 {@code status=1}；按 system 去重后按 {@code sort,id} 排序。</p>
 */
@Service
public class UserAccessService {

    private static final Comparator<SysSystem> SYSTEM_ORDER =
            Comparator.comparingInt((SysSystem system) -> system.getSort() == null ? 0 : system.getSort())
                    .thenComparing(SysSystem::getId);

    private final SysSystemRepository systemRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PlatformAdminService platformAdminService;

    public UserAccessService(SysSystemRepository systemRepository,
                             SysUserRoleRepository userRoleRepository,
                             PlatformAdminService platformAdminService) {
        this.systemRepository = systemRepository;
        this.userRoleRepository = userRoleRepository;
        this.platformAdminService = platformAdminService;
    }

    /**
     * 可访问系统实体列表（平台超管返回全部启用系统）。
     */
    @Transactional(readOnly = true)
    public List<SysSystem> accessibleSystems(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return List.of();
        }
        if (platformAdminService.isPlatformAdmin(externalUserId.trim())) {
            return allEnabledSystems();
        }
        List<Long> systemIds = userRoleRepository.findAccessibleSystemIds(externalUserId.trim());
        if (systemIds == null || systemIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = systemIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SysSystem> enabled = new LinkedHashMap<>();
        for (SysSystem system : systemRepository.findAllById(distinctIds)) {
            if (system.getStatus() != null && system.getStatus() == RbacConstants.STATUS_ENABLED
                    && (system.getIsPlatform() == null || system.getIsPlatform() != 1)) {
                enabled.put(system.getId(), system);
            }
        }
        List<SysSystem> result = new ArrayList<>(enabled.values());
        result.sort(SYSTEM_ORDER);
        return result;
    }

    /**
     * GET /api/me/systems：{@code {id,code,name,region,isDefault}}，第一条为默认系统。
     */
    @Transactional(readOnly = true)
    public List<AccessedSystemVO> accessedSystems(String externalUserId) {
        List<SysSystem> systems = accessibleSystems(externalUserId);
        List<AccessedSystemVO> result = new ArrayList<>(systems.size());
        for (int i = 0; i < systems.size(); i++) {
            SysSystem system = systems.get(i);
            result.add(new AccessedSystemVO(system.getId(), system.getCode(), system.getName(),
                    system.getRegion(), i == 0));
        }
        return result;
    }

    /**
     * GET /openapi/sdk/user-systems：{@code {code,name,region,isDefault}}（结构保持兼容）。
     */
    @Transactional(readOnly = true)
    public List<UserSystemItem> openApiUserSystems(String externalUserId) {
        List<AccessedSystemVO> systems = accessedSystems(externalUserId);
        List<UserSystemItem> result = new ArrayList<>(systems.size());
        for (AccessedSystemVO system : systems) {
            result.add(new UserSystemItem(system.code(), system.name(), system.region(), system.isDefault()));
        }
        return result;
    }

    /**
     * 用户是否可访问某系统（按有效角色推导）。
     */
    @Transactional(readOnly = true)
    public boolean canAccessSystem(String externalUserId, Long systemId) {
        if (systemId == null) {
            return false;
        }
        return accessibleSystems(externalUserId).stream()
                .anyMatch(system -> systemId.equals(system.getId()));
    }

    private List<SysSystem> allEnabledSystems() {
        List<SysSystem> systems = new ArrayList<>(
                systemRepository.findEnabledBusinessSystems(RbacConstants.STATUS_ENABLED));
        systems.sort(SYSTEM_ORDER);
        return systems;
    }
}
