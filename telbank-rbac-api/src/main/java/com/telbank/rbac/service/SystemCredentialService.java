package com.telbank.rbac.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.security.ManagementContext;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.web.dto.SystemCredentialDtos.SystemCredentialVO;

/**
 * 系统接入凭据查看（A18）。
 *
 * <p>{@link #current()} 严格取 X-System-Code 管理上下文系统；
 * {@link #bySystemId(Long)} 由 Controller 限制为平台超管（可跨系统，沿用 /api/systems 语义）。
 * 返回完整 appKey/appSecret，仅对有 {@code system:credential} 权限或平台超管开放。</p>
 */
@Service
public class SystemCredentialService {

    private final SysSystemMapper systemRepository;
    private final ManagementContextService managementContextService;

    public SystemCredentialService(SysSystemMapper systemRepository,
                                   ManagementContextService managementContextService) {
        this.systemRepository = systemRepository;
        this.managementContextService = managementContextService;
    }

    /** 当前管理上下文（X-System-Code）业务系统的完整接入凭据。 */
    @Transactional(readOnly = true)
    public SystemCredentialVO current() {
        ManagementContext context = managementContextService.currentContext()
                .orElseThrow(() -> ApiException.forbidden("X-System-Code header is required"));
        SysSystem system = systemRepository.findById(context.systemId())
                .orElseThrow(() -> ApiException.notFound("system not found: " + context.systemId()));
        return toVO(system);
    }

    /** 按 systemId 查看业务系统完整接入凭据（仅平台超管；平台系统/不存在返回 404）。 */
    @Transactional(readOnly = true)
    public SystemCredentialVO bySystemId(Long systemId) {
        SysSystem system = systemRepository.findById(systemId)
                .filter(found -> found.getIsPlatform() == null || found.getIsPlatform() != 1)
                .orElseThrow(() -> ApiException.notFound("system not found: " + systemId));
        return toVO(system);
    }

    private static SystemCredentialVO toVO(SysSystem system) {
        return new SystemCredentialVO(system.getId(), system.getCode(), system.getName(),
                system.getRegion(), system.getAppKey(), system.getAppSecret());
    }
}
