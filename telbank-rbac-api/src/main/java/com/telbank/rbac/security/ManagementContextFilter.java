package com.telbank.rbac.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.RbacConstants;

import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理端系统上下文过滤器：除 {@code /api/me}、{@code /api/me/systems}、{@code /api/me/bootstrap}
 * 外，所有 {@code /api/**} 请求必须携带合法 {@code X-System-Code}（平台超管可进入任意启用系统，
 * 普通用户必须在该系统内有有效角色），否则返回 403 信封。
 */
public class ManagementContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ManagementContextFilter.class);

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/me", "/api/me/systems", "/api/me/bootstrap");

    private final ManagementContextService managementContextService;
    private final JsonMapper jsonMapper;

    public ManagementContextFilter(ManagementContextService managementContextService,
                                   JsonMapper jsonMapper) {
        this.managementContextService = managementContextService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalize(path(request));
        return !path.startsWith("/api") || EXEMPT_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            ManagementContext context = managementContextService.requireContext();
            request.setAttribute(ManagementContextService.ATTR_MANAGEMENT_CONTEXT, context);
            filterChain.doFilter(request, response);
        } catch (ApiException ex) {
            writeError(response, ex.getStatus(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("failed to resolve management system context", ex);
            writeError(response, 500, "internal server error");
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), ApiResponse.error(status, message));
        response.flushBuffer();
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath == null || contextPath.isEmpty()
                ? uri : uri.substring(contextPath.length());
        return path == null ? "" : path;
    }

    private static String normalize(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 供测试/诊断使用：免上下文校验的路径。
     */
    static Set<String> exemptPaths() {
        return EXEMPT_PATHS;
    }

    static String headerName() {
        return RbacConstants.HEADER_SYSTEM_CODE;
    }
}
