package com.telbank.rbac.sdk.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.client.RbacOpenApiClient;

/**
 * 缓存刷新器：把权限中心的全量数据刷进本地缓存。
 *
 * <p>刷新失败只告警并保留旧缓存（可用性优先），由变更订阅与兜底轮询共同驱动。</p>
 */
public class RbacCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(RbacCacheRefresher.class);

    private final RbacOpenApiClient remoteClient;

    private final RbacMappingCache mappingCache;

    private final RbacPageActionCache pageActionCache;

    public RbacCacheRefresher(RbacOpenApiClient remoteClient, RbacMappingCache mappingCache,
            RbacPageActionCache pageActionCache) {
        if (remoteClient == null || mappingCache == null || pageActionCache == null) {
            throw new IllegalArgumentException("RbacCacheRefresher 依赖不能为空");
        }
        this.remoteClient = remoteClient;
        this.mappingCache = mappingCache;
        this.pageActionCache = pageActionCache;
    }

    /**
     * 全量刷新映射表 + 页面操作元数据；任一失败不影响另一个。
     */
    public void refreshMappingsAndPageActions() {
        refreshMappings();
        refreshPageActions();
    }

    /**
     * 全量刷新映射表。
     *
     * @return 是否成功
     */
    public boolean refreshMappings() {
        try {
            mappingCache.replace(remoteClient.fetchMappings());
            return true;
        } catch (RuntimeException e) {
            log.warn("RBAC 映射表全量刷新失败（保留旧缓存）: {}", e.toString());
            return false;
        }
    }

    /**
     * 全量刷新页面操作元数据。
     *
     * @return 是否成功
     */
    public boolean refreshPageActions() {
        try {
            pageActionCache.replace(remoteClient.fetchPageActions());
            return true;
        } catch (RuntimeException e) {
            log.warn("RBAC 页面操作元数据全量刷新失败（保留旧缓存）: {}", e.toString());
            return false;
        }
    }
}
