package com.telbank.rbac.security;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理端系统上下文解析（系统隔离模型）。
 *
 * <p>管理端 {@code /api/**} 以 {@code X-System-Code} 为管理上下文：</p>
 * <ul>
 *   <li>缺少请求头 → 403；</li>
 *   <li>系统不存在/停用/为平台系统 → 403；</li>
 *   <li>平台超管（数据驱动：拥有 {@code platform:admin} 权限）可进入任意启用业务系统；</li>
 *   <li>普通用户必须在该系统内有有效角色（{@code sys_user_role.status=1} 且时效内，
 *       角色启用），否则 403。</li>
 * </ul>
 */
@Component
public class ManagementContextService {

    /** 请求属性：已解析的管理上下文（由 {@link ManagementContextFilter} 写入）。 */
    public static final String ATTR_MANAGEMENT_CONTEXT = "rbac.management.context";

    private final CurrentUserProvider currentUserProvider;
    private final SysSystemRepository systemRepository;
    private final SysUserRoleRepository userRoleRepository;

    public ManagementContextService(CurrentUserProvider currentUserProvider,
                                    SysSystemRepository systemRepository,
                                    SysUserRoleRepository userRoleRepository) {
        this.currentUserProvider = currentUserProvider;
        this.systemRepository = systemRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 当前请求头中的原始 X-System-Code（未校验）；未携带时返回 null。
     */
    public String requestedSystemCode() {
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
     * 当前管理上下文：优先取请求属性（由过滤器写入），否则按请求头软解析。
     */
    public Optional<ManagementContext> currentContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object cached = servletAttributes.getRequest()
                    .getAttribute(ATTR_MANAGEMENT_CONTEXT);
            if (cached instanceof ManagementContext context) {
                return Optional.of(context);
            }
        }
        return resolveSoft();
    }

    /**
     * 按请求头软解析上下文：不抛异常，不满足条件返回 empty（bootstrap 等场景使用）。
     */
    public Optional<ManagementContext> resolveSoft() {
        String systemCode = requestedSystemCode();
        if (systemCode == null) {
            return Optional.empty();
        }
        Optional<CurrentUser> currentUser = currentUserProvider.get();
        if (currentUser.isEmpty()) {
            return Optional.empty();
        }
        SysSystem system = findEnabledSystem(systemCode).orElse(null);
        if (system == null) {
            return Optional.empty();
        }
        if (!currentUser.get().platformAdmin()
                && !hasEffectiveRole(system.getId(), currentUser.get().externalUserId())) {
            return Optional.empty();
        }
        return Optional.of(new ManagementContext(system.getId(), system.getCode()));
    }

    /**
     * 强制要求合法的管理上下文；失败抛 403（消息区分缺失请求头 / 系统非法 / 无该系统的有效角色）。
     */
    public ManagementContext requireContext() {
        String systemCode = requestedSystemCode();
        if (systemCode == null) {
            throw ApiException.forbidden("X-System-Code header is required");
        }
        CurrentUser currentUser = currentUserProvider.get()
                .orElseThrow(() -> ApiException.unauthorized("unauthorized"));
        SysSystem system = findEnabledSystem(systemCode)
                .orElseThrow(() -> ApiException.forbidden(
                        "invalid or disabled system context: " + systemCode));
        if (!currentUser.platformAdmin()
                && !hasEffectiveRole(system.getId(), currentUser.externalUserId())) {
            throw ApiException.forbidden("no access to system: " + systemCode);
        }
        return new ManagementContext(system.getId(), system.getCode());
    }

    /**
     * 用户在该系统内是否有有效角色（status=1 + 时效过滤 + 角色启用）。
     */
    public boolean hasEffectiveRole(Long systemId, String externalUserId) {
        if (systemId == null || externalUserId == null || externalUserId.isBlank()) {
            return false;
        }
        return userRoleRepository.countEffectiveRoles(systemId, externalUserId) > 0;
    }

    /**
     * 解析启用中的业务系统（X-System-Code 只能是业务系统，平台系统 is_platform=1 不允许作为管理上下文）。
     */
    public Optional<SysSystem> findEnabledSystem(String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            return Optional.empty();
        }
        return systemRepository.findByCode(systemCode.trim())
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .filter(system -> system.getIsPlatform() == null || system.getIsPlatform() != 1);
    }
}
