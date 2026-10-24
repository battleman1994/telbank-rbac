package com.telbank.rbac.security;

import org.springframework.stereotype.Component;

import com.telbank.rbac.common.ApiException;

/**
 * 系统隔离边界校验：非平台超管不得访问/查询当前管理上下文之外的系统数据。
 *
 * <p>平台超管（数据驱动 {@code platform:admin}）可跨系统；普通用户的 {@code systemId}
 * 必须与 {@code X-System-Code} 解析出的管理上下文一致，否则 403。</p>
 */
@Component
public class SystemScopeGuard {

    private final ManagementContextService managementContextService;
    private final CurrentUserProvider currentUserProvider;

    public SystemScopeGuard(ManagementContextService managementContextService,
                            CurrentUserProvider currentUserProvider) {
        this.managementContextService = managementContextService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 校验对目标系统数据的访问权限；targetSystemId 为空时不额外校验。
     */
    public void requireAccess(Long targetSystemId) {
        if (targetSystemId == null) {
            return;
        }
        CurrentUser currentUser = currentUserProvider.get()
                .orElseThrow(() -> ApiException.unauthorized("unauthorized"));
        if (currentUser.platformAdmin()) {
            return;
        }
        ManagementContext context = managementContextService.requireContext();
        if (!targetSystemId.equals(context.systemId())) {
            throw ApiException.forbidden("cross-system access denied");
        }
    }

    /**
     * 解析查询条件中的 systemId：平台超管保持原值（可跨系统）；普通用户缺省时收敛为当前上下文系统，
     * 指定其他系统时 403。
     */
    public Long resolveQuerySystemId(Long requestedSystemId) {
        CurrentUser currentUser = currentUserProvider.get()
                .orElseThrow(() -> ApiException.unauthorized("unauthorized"));
        if (currentUser.platformAdmin()) {
            return requestedSystemId;
        }
        ManagementContext context = managementContextService.requireContext();
        if (requestedSystemId == null) {
            return context.systemId();
        }
        if (!requestedSystemId.equals(context.systemId())) {
            throw ApiException.forbidden("cross-system access denied");
        }
        return requestedSystemId;
    }
}
