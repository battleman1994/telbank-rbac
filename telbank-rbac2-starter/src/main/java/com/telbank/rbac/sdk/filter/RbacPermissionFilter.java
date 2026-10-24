package com.telbank.rbac.sdk.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.telbank.rbac.sdk.cache.RbacMappingCache;
import com.telbank.rbac.sdk.cache.RbacPathMatcher;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.util.RbacRequestUtils;

/**
 * 接口权限拦截器（需求 §6.10，契约 §7.2）。
 *
 * <p>判定顺序（必须严格保持）：</p>
 * <ol>
 *   <li>基础设施路径（默认 /actuator/**、/error、/favicon.ico，可被 exclude-paths 覆盖；
 *       另始终放行 SDK 自身的 {@code endpoint.base-path/**}）→ 放行，不查任何缓存；</li>
 *   <li>CORS 预检 OPTIONS → 放行；</li>
 *   <li>{@code HTTP Method + Path} 匹配本地映射表：
 *       <ul>
 *         <li>未命中 → 直接放行（deny 策略时 403），不查用户权限集、不回源；</li>
 *         <li>命中 → 得到权限码，匿名用户 403；</li>
 *       </ul>
 *   </li>
 *   <li>查用户权限集缓存：有效 → 放行；无该码/时效已过 → 403；</li>
 *   <li>缓存未命中 → 回源（由缓存层完成）→ 判定；回源失败有旧缓存 → 用旧缓存 + 时钟偏移判定；
 *       无旧缓存 → 按 unmatched-strategy（默认 pass）；</li>
 *   <li>任何内部异常 → 告警 + 放行（可用性优先）。</li>
 * </ol>
 *
 * <p>注意：下游 Filter/业务异常不会被本类吞掉（chain.doFilter 在 try 之外调用）。</p>
 */
public class RbacPermissionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacPermissionFilter.class);

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private final RbacMappingCache mappingCache;

    private final RbacUserPermissionCache userPermissionCache;

    private final String systemCode;

    private final boolean denyUnmatched;

    private final List<RbacPathMatcher.Compiled> excludePatterns;

    public RbacPermissionFilter(RbacSdkProperties properties, RbacMappingCache mappingCache,
            RbacUserPermissionCache userPermissionCache) {
        if (properties == null || mappingCache == null || userPermissionCache == null) {
            throw new IllegalArgumentException("RbacPermissionFilter 依赖不能为空");
        }
        this.mappingCache = mappingCache;
        this.userPermissionCache = userPermissionCache;
        this.systemCode = properties.getSystemCode();
        this.denyUnmatched = properties.getInterceptor().isDenyUnmatched();
        this.excludePatterns = compileExcludePatterns(properties);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = RbacRequestUtils.resolvePath(request);
        String method = request.getMethod();
        if (isExcluded(path) || isPreflight(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String permissionCode;
        try {
            permissionCode = mappingCache.match(method, path);
        } catch (RuntimeException e) {
            log.error("RBAC 映射表匹配异常，按可用性优先放行: {} {}", method, path, e);
            filterChain.doFilter(request, response);
            return;
        }
        if (permissionCode == null) {
            if (denyUnmatched) {
                log.debug("RBAC 映射未命中且策略为 deny: {} {}", method, path);
                writeForbidden(response, "request is not configured in rbac mapping");
            } else {
                filterChain.doFilter(request, response);
            }
            return;
        }

        String externalUserId;
        try {
            externalUserId = RbacRequestUtils.resolveExternalUserId(request);
        } catch (RuntimeException e) {
            log.error("RBAC 解析当前用户失败，按可用性优先放行: {} {}", method, path, e);
            filterChain.doFilter(request, response);
            return;
        }
        if (externalUserId == null) {
            log.debug("RBAC 匿名用户访问受控接口被拒绝: {} {} -> {}", method, path, permissionCode);
            writeForbidden(response, "anonymous user is not allowed to access " + permissionCode);
            return;
        }

        boolean allowed;
        try {
            RbacUserPermissionCache.Decision decision =
                    userPermissionCache.check(externalUserId, systemCode, permissionCode);
            if (decision == RbacUserPermissionCache.Decision.ALLOW) {
                allowed = true;
            } else if (decision == RbacUserPermissionCache.Decision.DENY) {
                allowed = false;
            } else {
                // 回源失败且无旧缓存：按 unmatched-strategy 处理（默认 pass）
                allowed = !denyUnmatched;
                if (!allowed) {
                    log.warn("RBAC 权限中心不可用且无旧缓存，deny 策略拒绝请求: {} {} -> {}",
                            method, path, permissionCode);
                }
            }
        } catch (RuntimeException e) {
            log.error("RBAC 权限判定异常，按可用性优先放行: {} {}", method, path, e);
            allowed = true;
        }

        if (allowed) {
            filterChain.doFilter(request, response);
        } else {
            log.debug("RBAC 无权限被拒绝: user={}, {} {} -> {}", externalUserId, method, path, permissionCode);
            writeForbidden(response, "no permission: " + permissionCode);
        }
    }

    /**
     * 是否基础设施路径（配置的 exclude-paths + 始终放行的 SDK 自身 base-path）。
     */
    private boolean isExcluded(String path) {
        for (RbacPathMatcher.Compiled pattern : excludePatterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPreflight(String method) {
        return "OPTIONS".equalsIgnoreCase(method);
    }

    private static List<RbacPathMatcher.Compiled> compileExcludePatterns(RbacSdkProperties properties) {
        List<String> configured = properties.getInterceptor().getExcludePaths();
        List<String> patterns = new ArrayList<String>();
        if (configured != null) {
            patterns.addAll(configured);
        }
        // SDK 自身接口必须始终放行：deny 策略下前端仍要能拿到权限数据
        String basePath = properties.getEndpoint().getBasePath();
        if (basePath != null && !basePath.trim().isEmpty()) {
            String normalized = basePath.trim();
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            patterns.add(normalized + "/**");
        }
        List<RbacPathMatcher.Compiled> compiled = new ArrayList<RbacPathMatcher.Compiled>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.trim().isEmpty()) {
                continue;
            }
            RbacPathMatcher.Compiled item = RbacPathMatcher.compile(pattern.trim());
            if (item == null) {
                log.warn("忽略非法的 rbac-sdk.interceptor.exclude-paths 模式: {}", pattern);
                continue;
            }
            compiled.add(item);
        }
        return compiled;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(JSON_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\""
                + RbacRequestUtils.escapeJson(message) + "\"}");
    }
}
