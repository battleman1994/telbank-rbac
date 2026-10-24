package com.telbank.rbac.security;

import java.util.Optional;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.config.RbacProperties;

/**
 * 从 Spring Security 上下文解析当前用户：JWT {@code sub} 即 {@code external_user_id}
 * （需求 §5.1；本服务只校验 JWT，不签发）。
 */
@Component
public class CurrentUserProvider {

    private final RbacProperties properties;

    public CurrentUserProvider(RbacProperties properties) {
        this.properties = properties;
    }

    public Optional<CurrentUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String externalUserId = resolveSubject(authentication);
        if (externalUserId == null || externalUserId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CurrentUser(externalUserId, isPlatformAdmin(externalUserId)));
    }

    public CurrentUser require() {
        return get().orElseThrow(() -> ApiException.unauthorized("unauthorized"));
    }

    public String requireExternalUserId() {
        return require().externalUserId();
    }

    public boolean isPlatformAdmin(String externalUserId) {
        if (externalUserId == null || properties.getPlatformAdminIds() == null) {
            return false;
        }
        return properties.getPlatformAdminIds().stream()
                .anyMatch(id -> id != null && id.equals(externalUserId));
    }

    private String resolveSubject(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getSubject();
        }
        return authentication.getName();
    }
}
