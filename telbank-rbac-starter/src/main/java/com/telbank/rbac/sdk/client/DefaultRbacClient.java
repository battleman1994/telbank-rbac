package com.telbank.rbac.sdk.client;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * {@link RbacClient} 默认实现。
 *
 * <p>所有数据都走 SDK 内部缓存与开放接口客户端，与拦截器共享同一份缓存：
 * {@code /rbac/me/bootstrap} 预热过的用户权限集，这里可直接命中。</p>
 */
public class DefaultRbacClient implements RbacClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultRbacClient.class);

    private final RbacOpenApiClient remoteClient;

    private final RbacSdkProperties properties;

    private final RbacUserPermissionCache userPermissionCache;

    private final RbacPageActionCache pageActionCache;

    private final RbacCacheRefresher cacheRefresher;

    public DefaultRbacClient(RbacOpenApiClient remoteClient, RbacSdkProperties properties,
            RbacUserPermissionCache userPermissionCache, RbacPageActionCache pageActionCache,
            RbacCacheRefresher cacheRefresher) {
        if (remoteClient == null || properties == null || userPermissionCache == null
                || pageActionCache == null || cacheRefresher == null) {
            throw new IllegalArgumentException("DefaultRbacClient 依赖不能为空");
        }
        this.remoteClient = remoteClient;
        this.properties = properties;
        this.userPermissionCache = userPermissionCache;
        this.pageActionCache = pageActionCache;
        this.cacheRefresher = cacheRefresher;
    }

    @Override
    public List<SystemInfo> getSystems(String externalUserId) {
        if (isNotBlank(externalUserId)) {
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

    @Override
    public Set<String> getPermissions(String externalUserId, String systemCode) {
        requireConfiguredSystem(systemCode);
        return userPermissionCache.getValidPermissions(externalUserId, systemCode);
    }

    @Override
    public List<PageAction> getPageActions(String systemCode, String pageCode) {
        requireConfiguredSystem(systemCode);
        if (!pageActionCache.isLoaded()) {
            cacheRefresher.refreshPageActions();
        }
        return pageActionCache.get(pageCode);
    }

    @Override
    public boolean isPlatformAdmin(String externalUserId) {
        try {
            return userPermissionCache.isPlatformAdmin(externalUserId, properties.getSystemCode());
        } catch (RuntimeException e) {
            log.warn("判断平台超管失败，按非超管处理: {}", e.toString());
            return false;
        }
    }

    private void requireConfiguredSystem(String systemCode) {
        if (systemCode == null || !systemCode.equals(properties.getSystemCode())) {
            throw new IllegalArgumentException("当前 SDK 只服务系统[" + properties.getSystemCode()
                    + "]，不支持查询系统[" + systemCode + "]");
        }
    }

    private SystemInfo localSystem() {
        return new SystemInfo(properties.getSystemCode(), properties.getSystemCode(), null, Boolean.TRUE);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
