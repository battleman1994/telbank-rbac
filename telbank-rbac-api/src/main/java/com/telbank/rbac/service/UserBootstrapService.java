package com.telbank.rbac.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;

/**
 * 管理端 bootstrap（契约 §3.1）：当前用户 + 当前系统 + 当前系统内有效权限码 + 可访问系统。
 *
 * <p>系统隔离模型下不再有固定管理上下文（{@code X-System-Code} 只能是业务系统）：{@code currentSystemCode} 取请求头 X-System-Code
 * （合法且有权限时），否则取第一个可访问系统；普通用户权限码为当前系统内经过时效过滤的集合，
 * 平台超管为 {@code ["*"]}；无任何可访问系统时 currentSystemCode 为 null、permissions 为空。</p>
 */
@Service
public class UserBootstrapService {

    private final UserAccessService userAccessService;
    private final SysSystemMapper systemRepository;
    private final PermissionQueryService permissionQueryService;

    public UserBootstrapService(UserAccessService userAccessService,
                                SysSystemMapper systemRepository,
                                PermissionQueryService permissionQueryService) {
        this.userAccessService = userAccessService;
        this.systemRepository = systemRepository;
        this.permissionQueryService = permissionQueryService;
    }

    /**
     * 构造管理前端 bootstrap 数据。
     *
     * @param externalUserId      当前用户（JWT sub）
     * @param platformAdmin       是否平台超管
     * @param requestedSystemCode 请求头 X-System-Code（合法时作为当前系统）
     */
    @Transactional(readOnly = true)
    public BootstrapVO bootstrap(String externalUserId, boolean platformAdmin, String requestedSystemCode) {
        List<AccessedSystemVO> systems = userAccessService.accessedSystems(externalUserId);
        SysSystem currentSystem = resolveCurrentSystem(requestedSystemCode, platformAdmin, systems);
        List<String> permissions = resolvePermissions(externalUserId, platformAdmin, currentSystem);
        return new BootstrapVO(externalUserId, platformAdmin,
                currentSystem == null ? null : currentSystem.getCode(), permissions, systems);
    }

    /**
     * 当前系统：合法的 X-System-Code（存在/启用 + 用户可访问；超管任意启用系统）优先，
     * 否则回退到第一个可访问系统。
     */
    private SysSystem resolveCurrentSystem(String requestedSystemCode, boolean platformAdmin,
                                           List<AccessedSystemVO> systems) {
        if (requestedSystemCode != null && !requestedSystemCode.isBlank()) {
            SysSystem requested = findEnabledSystem(requestedSystemCode.trim());
            if (requested != null) {
                boolean accessible = platformAdmin
                        || systems.stream().anyMatch(system -> requested.getId().equals(system.id()));
                if (accessible) {
                    return requested;
                }
            }
        }
        if (systems.isEmpty()) {
            return null;
        }
        return systemRepository.findById(systems.get(0).id())
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .filter(system -> system.getIsPlatform() == null || system.getIsPlatform() != 1)
                .orElse(null);
    }

    private SysSystem findEnabledSystem(String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            return null;
        }
        return systemRepository.findByCode(systemCode.trim())
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .filter(system -> system.getIsPlatform() == null || system.getIsPlatform() != 1)
                .orElse(null);
    }

    private List<String> resolvePermissions(String externalUserId, boolean platformAdmin, SysSystem currentSystem) {
        if (platformAdmin) {
            return List.of(RbacConstants.PLATFORM_ADMIN_PERMISSION_CODE);
        }
        if (currentSystem == null) {
            return List.of();
        }
        Set<String> codes = permissionQueryService.effectiveCodes(currentSystem.getId(), externalUserId);
        return List.copyOf(codes);
    }
}
