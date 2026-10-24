package com.telbank.rbac.sdk.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

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
import com.telbank.rbac.sdk.util.RbacPermissionMatcher;
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
 * <p>v2.0 系统隔离语义：{@code /rbac/me/systems} 返回「用户有有效角色的业务系统」（由权限中心
 * 按有效 user_role 推导并排除 platform 平台系统），用户无有效角色时返回空数组；不再有
 * 「用户-系统开通」概念。仅回源失败时降级为当前配置系统单例。</p>
 *
 * <p>A 方案通配权限：{@code /rbac/me/bootstrap} 的 permissions 原样返回缓存中的有效权限码，
 * 可能包含 {@code *}（全量）或 {@code 前缀:*}（如 {@code order:*}）；前端 {@code hasPerm}
 * 需按「精确 / * / 前缀:*」同一口径匹配。</p>
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
     * 当前用户有有效角色的业务系统列表（v2.0 系统隔离模型）。
     *
     * <p>权限中心按「有效 user_role」推导业务系统（排除 platform 平台系统），SDK 原样透传；
     * 空列表表示用户没有有效角色，属于正常业务结果，不降级。仅当回源失败/接口不可用时，
     * 才降级为当前配置系统单例（可用性优先）。匿名请求无法推导，返回本系统单例。</p>
     */
    @GetMapping("/me/systems")
    public List<SystemInfo> getSystems(HttpServletRequest request) {
        String externalUserId = RbacRequestUtils.resolveExternalUserId(request);
        if (externalUserId == null) {
            // 匿名请求无法按有效角色推导（认证由宿主认证链负责）
            return Collections.singletonList(localSystem());
        }
        try {
            List<SystemInfo> systems = remoteClient.fetchUserSystems(externalUserId);
            // 空列表 = 用户没有有效角色，原样透传，不能降级为配置系统单例
            return systems == null ? Collections.<SystemInfo>emptyList() : systems;
        } catch (RuntimeException e) {
            log.warn("查询用户可访问系统失败，降级为本系统单例: {}", e.toString());
            return Collections.singletonList(localSystem());
        }
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
            // A 方案：全量通配 * 表示当前系统全部权限（平台超管/系统 admin 均下发 *）；
            // 前缀通配（如 order:*）仍原样放入 permissions，由前端 hasPerm 用同一口径匹配。
            if (RbacPermissionMatcher.hasWildcardAll(validPermissions)) {
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
