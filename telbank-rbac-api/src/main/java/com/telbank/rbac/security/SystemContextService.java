package com.telbank.rbac.security;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理端系统上下文：{@code X-System-Code} → {@code sys_system.id}（契约 §1.5）。
 */
@Component
public class SystemContextService {

    private final SysSystemRepository systemRepository;

    public SystemContextService(SysSystemRepository systemRepository) {
        this.systemRepository = systemRepository;
    }

    /**
     * 当前请求头中的系统编码；未携带时返回 null。
     */
    public String currentSystemCode() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String header = request.getHeader(RbacConstants.HEADER_SYSTEM_CODE);
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.trim();
    }

    /**
     * 当前请求头对应的启用系统 ID；缺失或未知/停用时返回 empty。
     */
    public Optional<Long> currentSystemId() {
        String systemCode = currentSystemCode();
        if (systemCode == null) {
            return Optional.empty();
        }
        return systemRepository.findByCode(systemCode)
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .map(SysSystem::getId);
    }

    public SysSystem requireSystemByCode(String systemCode) {
        return systemRepository.findByCode(systemCode)
                .orElseThrow(() -> com.telbank.rbac.common.ApiException.notFound(
                        "system not found: " + systemCode));
    }
}
