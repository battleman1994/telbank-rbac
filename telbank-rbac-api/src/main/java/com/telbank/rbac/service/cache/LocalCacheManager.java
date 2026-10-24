package com.telbank.rbac.service.cache;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.entity.SysPermission;

/**
 * 本地（Caffeine）一级缓存。缓存的是实体列表/元数据，不缓存最终鉴权结果；
 * 所有写操作由对应 Service 主动失效，TTL 仅作为兜底。
 */
public class LocalCacheManager {

    private final Cache<Long, List<SysPermission>> permissionCache;
    private final Cache<String, List<SysPageAction>> pageActionCache;

    public LocalCacheManager(Duration ttl, long maximumSize) {
        this.permissionCache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
        this.pageActionCache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
    }

    public List<SysPermission> getPermissions(Long systemId, Supplier<List<SysPermission>> loader) {
        return permissionCache.get(systemId, key -> loader.get());
    }

    public List<SysPageAction> getPageActions(Long systemId, String pageCode,
                                              Supplier<List<SysPageAction>> loader) {
        String key = pageActionKey(systemId, pageCode);
        return pageActionCache.get(key, k -> loader.get());
    }

    public void invalidatePermissions(Long systemId) {
        if (systemId == null) {
            permissionCache.invalidateAll();
        } else {
            permissionCache.invalidate(systemId);
        }
    }

    public void invalidatePageActions(Long systemId) {
        if (systemId == null) {
            pageActionCache.invalidateAll();
            return;
        }
        String prefix = systemId + "|";
        pageActionCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 变更通知的本地兜底：同一 JVM 内的缓存立即失效。 */
    public void invalidateAll() {
        permissionCache.invalidateAll();
        pageActionCache.invalidateAll();
    }

    private static String pageActionKey(Long systemId, String pageCode) {
        return systemId + "|" + (pageCode == null ? "" : pageCode);
    }
}
