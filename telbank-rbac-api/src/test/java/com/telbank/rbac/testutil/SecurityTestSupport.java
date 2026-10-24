package com.telbank.rbac.testutil;

import java.util.List;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 单测辅助：构造 Spring Security 认证上下文与带 X-System-Code 的请求上下文。
 */
public final class SecurityTestSupport {

    private SecurityTestSupport() {
    }

    public static void authenticate(String externalUserId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(externalUserId, null, List.of()));
    }

    public static MockHttpServletRequest requestWithSystemCode(String systemCode) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/roles");
        if (systemCode != null) {
            request.addHeader("X-System-Code", systemCode);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    public static void reset() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }
}
