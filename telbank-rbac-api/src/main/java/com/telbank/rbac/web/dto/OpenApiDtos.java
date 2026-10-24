package com.telbank.rbac.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * SDK 开放接口 DTO（契约 §4.2）。
 */
public final class OpenApiDtos {

    private OpenApiDtos() {
    }

    public record MappingItem(String method, String pattern, String code) {
    }

    public record MappingsResponse(String systemCode, List<MappingItem> mappings) {
    }

    public record PermissionItem(String code, Instant validFrom, Instant validUntil) {
    }

    public record UserPermissionsResponse(List<PermissionItem> permissions, Instant serverTime) {
    }

    public record PageActionItem(Long id,
                                 String pageCode,
                                 String code,
                                 String text,
                                 String type,
                                 String action,
                                 String config,
                                 Integer sort) {
    }

    public record PageActionsResponse(String systemCode, List<PageActionItem> pageActions) {
    }

    public record ConnectionResponse(String channel,
                                     String region,
                                     String host,
                                     int port,
                                     String password,
                                     String channelPrefix) {
    }

    public record EndpointReport(String method, String path, String candidateCode, String source) {
    }

    public record EndpointsSyncRequest(String systemCode, List<EndpointReport> endpoints) {
    }

    public record EndpointsSyncResponse(int received, int created, int updated) {
    }

    /** GET /openapi/sdk/user-systems 的元素（契约 §4.2）。 */
    public record UserSystemItem(String code, String name, String region, boolean isDefault) {
    }
}
