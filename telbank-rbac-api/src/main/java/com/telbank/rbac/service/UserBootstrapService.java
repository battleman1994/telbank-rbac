package com.telbank.rbac.service;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;

/**
 * 管理端 bootstrap（契约 §3.1 新增）：权限中心自身接入 RBAC 的当前用户上下文。
 *
 * <p>权限中心管理端 {@code /api/**} 的管理权限上下文固定为 {@code rbac.management-system-code}
 * 指向的系统（默认 {@code rbac}）；业务系统选择只通过 {@code systemId} 参数表达，不再影响鉴权上下文。</p>
 */
@Service
public class UserBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapService.class);

    private final RbacProperties properties;
    private final SysSystemRepository systemRepository;
    private final PermissionQueryService permissionQueryService;
    private final UserSystemService userSystemService;

    public UserBootstrapService(RbacProperties properties,
                                SysSystemRepository systemRepository,
                                PermissionQueryService permissionQueryService,
                                UserSystemService userSystemService) {
        this.properties = properties;
        this.systemRepository = systemRepository;
        this.permissionQueryService = permissionQueryService;
        this.userSystemService = userSystemService;
    }

    /**
     * 构造管理前端 bootstrap 数据。
     *
     * @param externalUserId 当前用户（JWT sub）
     * @param platformAdmin  是否平台超管
     */
    @Transactional(readOnly = true)
    public BootstrapVO bootstrap(String externalUserId, boolean platformAdmin) {
        String managementSystemCode = properties.getManagementSystemCode();
        List<String> permissions = resolveManagementPermissions(externalUserId, platformAdmin,
                managementSystemCode);
        List<AccessedSystemVO> systems = userSystemService.meSystems(externalUserId);
        return new BootstrapVO(externalUserId, platformAdmin, managementSystemCode, permissions, systems);
    }

    /**
     * 管理系统内的有效权限码（时效过滤 + is_public 已由 {@link PermissionQueryService} 保证）。
     *
     * <p>平台超管返回 {@code ["*"]}；管理系统不存在/停用时返回空集合并记录 warn（管理端仍可登录，
     * 只是所有 @perm 校验会 403，便于运维发现配置缺失）。</p>
     */
    private List<String> resolveManagementPermissions(String externalUserId, boolean platformAdmin,
                                                      String managementSystemCode) {
        if (platformAdmin) {
            return List.of(RbacConstants.PLATFORM_ADMIN_PERMISSION_CODE);
        }
        if (managementSystemCode == null || managementSystemCode.isBlank()) {
            log.warn("rbac.management-system-code is not configured, management permissions are empty");
            return List.of();
        }
        SysSystem managementSystem = systemRepository.findByCode(managementSystemCode)
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .orElse(null);
        if (managementSystem == null) {
            log.warn("management system not found or disabled: code={}, management permissions are empty",
                    managementSystemCode);
            return List.of();
        }
        Set<String> codes = permissionQueryService.effectiveCodes(managementSystem.getId(), externalUserId);
        return List.copyOf(codes);
    }
}
