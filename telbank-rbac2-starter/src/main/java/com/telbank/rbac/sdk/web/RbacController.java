package com.telbank.rbac.sdk.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.PageAction;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.client.SystemInfo;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.util.RbacRequestUtils;

/**
 * SDK 自动注册的业务侧查询接口（需求 §6.4，契约 §7.4）。
 *
 * <pre>
 * GET {base-path}/me/systems    -&gt; [{"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}]
 * GET {base-path}/me/bootstrap  -&gt; {"permissions":[...],"pageActions":[...],"platformAdmin":false}
 * </pre>
 *
 * <p>路径前缀由 {@code rbac-sdk.endpoint.base-path} 控制（默认 /rbac）。不返回菜单树。</p>
 *
 * <p>调用 {@code /rbac/me/bootstrap} 会预热用户权限集缓存（命中缓存则不会额外回源）。</p>
 */
@RestController
@RequestMapping(path = "${rbac-sdk.endpoint.base-path:/rbac}")
public class RbacController {

    private static final Logger log = LoggerFactory.getLogger(RbacController.class);

    private final RbacSdkProperties properties;

    private final RbacOpenApiClient remoteClient;

    private final RbacUserPermissionCache userPermissionCache;

    private final RbacPageActionCache pageActionCache;

    public RbacController(RbacSdkProperties properties, RbacOpenApiClient remoteClient,
            RbacUserPermissionCache userPermissionCache, RbacPageActionCache pageActionCache) {
        if (properties == null || remoteClient == null || userPermissionCache == null
                || pageActionCache == null) {
            throw new IllegalArgumentException("RbacController 依赖不能为空");
        }
        this.properties = properties;
        this.remoteClient = remoteClient;
        this.userPermissionCache = userPermissionCache;
        this.pageActionCache = pageActionCache;
    }

    /**
     * 当前用户可访问的系统列表。权限中心不可用/接口缺失时降级为当前配置系统单例。
     */
    @GetMapping("/me/systems")
    public List<SystemInfo> getSystems(HttpServletRequest request) {
        String externalUserId = RbacRequestUtils.resolveExternalUserId(request);
        if (externalUserId != null) {
            try {
                List<SystemInfo> systems = remoteClient.fetchUserSystems(externalUserId);
                if (systems != null && !systems.isEmpty()) {
                    return systems;
                }
            } catch (RuntimeException e) {
                log.warn("查询用户可访问系统失败，降级为本系统单例: {}", e.toString());
            }
        }
        return Collections.singletonList(localSystem());
    }

    /**
     * 当前用户权限引导数据（permissions + pageActions + platformAdmin），并预热用户权限集缓存。
     */
    @GetMapping("/me/bootstrap")
    public BootstrapResponse getBootstrap(HttpServletRequest request) {
        String externalUserId = RbacRequestUtils.resolveExternalUserId(request);
        if (externalUserId == null) {
            // 契约 §7.6：anonymous 返回空权限
            log.debug("RBAC bootstrap 未取到用户身份，返回空权限");
            return new BootstrapResponse(Collections.<String>emptyList(),
                    Collections.<PageAction>emptyList(), false);
        }
        String systemCode = properties.getSystemCode();
        List<String> permissions = new ArrayList<String>();
        boolean platformAdmin = false;
        try {
            // 预热用户权限集缓存：命中缓存时不回源
            Set<String> validPermissions = userPermissionCache.getValidPermissions(externalUserId, systemCode);
            if (validPermissions.contains(RbacUserPermissionCache.PLATFORM_ADMIN_CODE)) {
                platformAdmin = true;
                permissions = new ArrayList<String>(
                        Collections.singletonList(RbacUserPermissionCache.PLATFORM_ADMIN_CODE));
            } else {
                permissions = new ArrayList<String>(validPermissions);
            }
        } catch (RuntimeException e) {
            log.warn("预热用户权限集失败，本次返回空权限（业务请求仍按降级策略处理）: {}", e.toString());
        }
        List<PageAction> pageActions = pageActionCache.getAll();
        return new BootstrapResponse(permissions, pageActions, platformAdmin);
    }

    private SystemInfo localSystem() {
        return new SystemInfo(properties.getSystemCode(), properties.getSystemCode(), null, Boolean.TRUE);
    }

    /**
     * {@code /rbac/me/bootstrap} 响应体。
     */
    public static class BootstrapResponse {

        private final List<String> permissions;

        private final List<PageAction> pageActions;

        private final boolean platformAdmin;

        public BootstrapResponse(List<String> permissions, List<PageAction> pageActions,
                boolean platformAdmin) {
            this.permissions = permissions;
            this.pageActions = pageActions;
            this.platformAdmin = platformAdmin;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public List<PageAction> getPageActions() {
            return pageActions;
        }

        public boolean isPlatformAdmin() {
            return platformAdmin;
        }
    }
}
