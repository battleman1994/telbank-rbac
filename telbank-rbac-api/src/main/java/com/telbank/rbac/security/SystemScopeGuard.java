package com.telbank.rbac.security;

import org.springframework.stereotype.Component;

import com.telbank.rbac.common.ApiException;

/**
 * 严格系统隔离边界校验（超管同样受限）。
 *
 * <p>管理端任何数据查询/写入都必须限定在 {@code X-System-Code} 解析出的管理上下文系统：</p>
 * <ul>
 *   <li>by-id 接口加载实体后调用 {@link #requireAccess(Long)}，实体 systemId 必须等于上下文；
 *       平台超管也不例外；</li>
 *   <li>列表接口的 systemId 缺省时收敛为上下文系统，显式指定其他系统一律 403；</li>
 *   <li>跨系统访问统一返回 403。</li>
 * </ul>
 */
@Component
public class SystemScopeGuard {

    private final ManagementContextService managementContextService;

    public SystemScopeGuard(ManagementContextService managementContextService) {
        this.managementContextService = managementContextService;
    }

    /**
     * 校验对目标系统数据的访问权限；targetSystemId 为空时不额外校验。
     * 超管无跨系统旁路：targetSystemId 必须等于当前管理上下文。
     */
    public void requireAccess(Long targetSystemId) {
        if (targetSystemId == null) {
            return;
        }
        ManagementContext context = managementContextService.requireContext();
        if (!targetSystemId.equals(context.systemId())) {
            throw ApiException.forbidden("cross-system access denied");
        }
    }

    /**
     * 解析查询条件中的 systemId：缺省时收敛为当前上下文系统；指定其他系统时 403（超管同样受限）。
     */
    public Long resolveQuerySystemId(Long requestedSystemId) {
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
