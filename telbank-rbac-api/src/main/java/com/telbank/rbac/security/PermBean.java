package com.telbank.rbac.security;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.telbank.rbac.common.PermissionMatcher;
import com.telbank.rbac.service.PermissionQueryService;

/**
 * 方法级权限断言 Bean，名称为 {@code perm}（契约 §1.6）。
 *
 * <p>Controller 用法：{@code @PreAuthorize("@perm.has('role:manage')")}。</p>
 *
 * <p>查询逻辑（系统隔离模型）：JWT sub → external_user_id；平台超管（平台系统内 {@code *}，数据驱动）
 * 短路放行；否则在当前管理上下文系统（X-System-Code）内查询 {@code sys_user_role} 命中的
 * 权限码，查询自带时效过滤，并用 {@link PermissionMatcher} 做通配匹配（{@code *} / {@code 前缀:*} / 精确）。
 * 缺 X-System-Code 且非平台超管 → 无权限。</p>
 */
@Component("perm")
public class PermBean {

    private final CurrentUserProvider currentUserProvider;
    private final ManagementContextService managementContextService;
    private final PermissionQueryService permissionQueryService;

    public PermBean(CurrentUserProvider currentUserProvider,
                    ManagementContextService managementContextService,
                    PermissionQueryService permissionQueryService) {
        this.currentUserProvider = currentUserProvider;
        this.managementContextService = managementContextService;
        this.permissionQueryService = permissionQueryService;
    }

    /**
     * 是否平台超管（用于 {@code /api/systems/**} 等超管专属接口）。
     */
    public boolean isPlatformAdmin() {
        return currentUserProvider.get().map(CurrentUser::platformAdmin).orElse(false);
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
        Optional<ManagementContext> context = managementContextService.currentContext();
        if (context.isEmpty()) {
            return false;
        }
        Set<String> granted = permissionQueryService.effectiveCodes(context.get().systemId(),
                currentUser.get().externalUserId());
        return PermissionMatcher.matchesAll(granted, codes);
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
        Optional<ManagementContext> context = managementContextService.currentContext();
        if (context.isEmpty()) {
            return false;
        }
        Set<String> granted = permissionQueryService.effectiveCodes(context.get().systemId(),
                currentUser.get().externalUserId());
        return PermissionMatcher.matchesAny(granted, codes);
    }
}
