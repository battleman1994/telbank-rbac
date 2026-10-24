package com.telbank.rbac.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;

/**
 * 平台超管判定（数据驱动，不读取配置文件）。
 *
 * <p>平台超管 = 用户在平台系统（{@code sys_system.is_platform=1}）内拥有有效角色，
 * 且该角色授予了 {@code platform:admin} 权限（时效过滤由 SQL 保证）。</p>
 */
@Service
public class PlatformAdminService {

    private final SysSystemRepository systemRepository;
    private final SysUserRoleRepository userRoleRepository;

    public PlatformAdminService(SysSystemRepository systemRepository,
                                SysUserRoleRepository userRoleRepository) {
        this.systemRepository = systemRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 当前平台系统（is_platform=1，启用）；不存在时返回 empty。
     */
    @Transactional(readOnly = true)
    public Optional<SysSystem> platformSystem() {
        return systemRepository.findFirstByIsPlatformOrderByIdAsc(1)
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED);
    }

    /**
     * 用户是否平台超管（拥有平台系统内有效 {@code platform:admin} 权限）。
     */
    @Transactional(readOnly = true)
    public boolean isPlatformAdmin(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return false;
        }
        SysSystem platformSystem = platformSystem().orElse(null);
        if (platformSystem == null) {
            return false;
        }
        return userRoleRepository.countEffectivePermission(platformSystem.getId(), externalUserId.trim(),
                RbacConstants.PERM_PLATFORM_ADMIN) > 0;
    }
}
