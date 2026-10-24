package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysPageActionMapper;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.web.dto.OpenApiDtos.ConnectionResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.MappingItem;
import com.telbank.rbac.web.dto.OpenApiDtos.MappingsResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.PageActionItem;
import com.telbank.rbac.web.dto.OpenApiDtos.PageActionsResponse;

/**
 * SDK 开放接口查询：映射表、页面操作元数据、区域 Redis 连接（契约 §4.2 / §6.8 / §6.11）。
 */
@Service
public class OpenApiQueryService {

    private final SysSystemMapper systemRepository;
    private final SysPermissionMapper permissionRepository;
    private final SysPageActionMapper pageActionRepository;
    private final LocalCacheManager localCacheManager;
    private final RbacProperties properties;

    public OpenApiQueryService(SysSystemMapper systemRepository,
                               SysPermissionMapper permissionRepository,
                               SysPageActionMapper pageActionRepository,
                               LocalCacheManager localCacheManager,
                               RbacProperties properties) {
        this.systemRepository = systemRepository;
        this.permissionRepository = permissionRepository;
        this.pageActionRepository = pageActionRepository;
        this.localCacheManager = localCacheManager;
        this.properties = properties;
    }

    /**
     * 映射表下发：method/api_pattern 均非空且 status=1；pattern 原样下发。
     */
    @Transactional(readOnly = true)
    public MappingsResponse mappings(String systemCode) {
        SysSystem system = requireEnabledSystem(systemCode);
        List<SysPermission> permissions = localCacheManager.getPermissions(system.getId(),
                () -> permissionRepository.findAllBySystemIdOrderBySortAscIdAsc(system.getId()));
        List<MappingItem> mappings = new ArrayList<>();
        for (SysPermission permission : permissions) {
            if (permission.getStatus() == null || permission.getStatus() != RbacConstants.STATUS_ENABLED) {
                continue;
            }
            String method = permission.getMethod();
            String pattern = permission.getApiPattern();
            if (method == null || method.isBlank() || pattern == null || pattern.isBlank()) {
                continue;
            }
            mappings.add(new MappingItem(method, pattern, permission.getCode()));
        }
        return new MappingsResponse(system.getCode(), mappings);
    }

    /**
     * 页面操作元数据下发；pageCode 为空时返回系统全部启用配置。
     */
    @Transactional(readOnly = true)
    public PageActionsResponse pageActions(String systemCode, String pageCode) {
        SysSystem system = requireEnabledSystem(systemCode);
        String normalizedPageCode = pageCode == null || pageCode.isBlank() ? null : pageCode.trim();
        List<SysPageAction> actions = localCacheManager.getPageActions(system.getId(), null,
                () -> pageActionRepository.findBySystemIdAndStatusOrderBySortAscIdAsc(
                        system.getId(), RbacConstants.STATUS_ENABLED));
        List<PageActionItem> items = new ArrayList<>();
        for (SysPageAction action : actions) {
            if (normalizedPageCode != null && !normalizedPageCode.equals(action.getPageCode())) {
                continue;
            }
            if (action.getStatus() == null || action.getStatus() != RbacConstants.STATUS_ENABLED) {
                continue;
            }
            items.add(new PageActionItem(action.getId(), action.getPageCode(), action.getCode(),
                    action.getText(), action.getType(), action.getAction(), action.getConfig(),
                    action.getSort()));
        }
        return new PageActionsResponse(system.getCode(), items);
    }

    /**
     * 区域 Redis 连接信息（契约 §4.2 / §6.11）。
     *
     * <p>即使 {@code rbac.redis.enabled=false} 也返回配置的区域连接信息（SDK 侧自行降级短轮询）。</p>
     */
    @Transactional(readOnly = true)
    public ConnectionResponse connection(String systemCode) {
        SysSystem system = requireEnabledSystem(systemCode);
        RbacProperties.Region region = properties.getRegions().get(system.getRegion());
        String host = region == null || region.getHost() == null ? "" : region.getHost();
        int port = region == null ? 0 : region.getPort();
        String password = region == null || region.getPassword() == null ? "" : region.getPassword();
        String channelPrefix = region != null && region.getChannelPrefix() != null
                && !region.getChannelPrefix().isBlank()
                ? region.getChannelPrefix()
                : "rbac:change:" + system.getRegion() + ":";
        return new ConnectionResponse("redis", system.getRegion(), host, port, password, channelPrefix);
    }

    @Transactional(readOnly = true)
    public SysSystem requireEnabledSystem(String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            throw ApiException.badRequest("systemCode is required");
        }
        return systemRepository.findByCode(systemCode.trim())
                .filter(system -> system.getStatus() != null
                        && system.getStatus() == RbacConstants.STATUS_ENABLED)
                .orElseThrow(() -> ApiException.notFound("system not found or disabled: " + systemCode));
    }
}
