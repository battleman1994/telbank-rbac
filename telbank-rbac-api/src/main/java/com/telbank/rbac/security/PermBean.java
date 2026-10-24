package com.telbank.rbac.security;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.telbank.rbac.service.PermissionQueryService;

/**
 * 方法级权限断言 Bean，名称为 {@code perm}（契约 §1.6）。
 *
 * <p>Controller 用法：{@code @PreAuthorize("@perm.has('rbac:system:manage')")}。</p>
 *
 * <p>查询逻辑（需求 §5.2）：JWT sub → external_user_id；命中平台超管直接放行；
 * 否则按 {@code X-System-Code} 对应 system_id 查 {@code sys_user_role} 命中的权限码，
 * 查询自带时效过滤。缺 X-System-Code 且非平台超管 → 无权限。</p>
 */
@Component("perm")
public class PermBean {

    private final CurrentUserProvider currentUserProvider;
    private final SystemContextService systemContextService;
    private final PermissionQueryService permissionQueryService;

    public PermBean(CurrentUserProvider currentUserProvider,
                    SystemContextService systemContextService,
                    PermissionQueryService permissionQueryService) {
        this.currentUserProvider = currentUserProvider;
        this.systemContextService = systemContextService;
        this.permissionQueryService = permissionQueryService;
    }

    /**
     * 多权限码 AND 语义；空数组返回 false。
     */
    public boolean has(String... codes) {
        if (codes == null || codes.length == 0) {
            return false;
        }
        Optional<CurrentUser> currentUser = currentUserProvider.get();
        if (currentUser.isEmpty()) {
            return false;
        }
        if (currentUser.get().platformAdmin()) {
            return true;
        }
        Optional<Long> systemId = systemContextService.currentSystemId();
        if (systemId.isEmpty()) {
            return false;
        }
        Set<String> granted = permissionQueryService.effectiveCodes(systemId.get(),
                currentUser.get().externalUserId());
        for (String code : codes) {
            if (code == null || code.isBlank() || !granted.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 多权限码 OR 语义；空数组返回 false。
     */
    public boolean hasAny(String... codes) {
        if (codes == null || codes.length == 0) {
            return false;
        }
        Optional<CurrentUser> currentUser = currentUserProvider.get();
        if (currentUser.isEmpty()) {
            return false;
        }
        if (currentUser.get().platformAdmin()) {
            return true;
        }
        Optional<Long> systemId = systemContextService.currentSystemId();
        if (systemId.isEmpty()) {
            return false;
        }
        Set<String> granted = permissionQueryService.effectiveCodes(systemId.get(),
                currentUser.get().externalUserId());
        for (String code : codes) {
            if (code != null && !code.isBlank() && granted.contains(code)) {
                return true;
            }
        }
        return false;
    }
}
