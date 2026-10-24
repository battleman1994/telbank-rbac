package com.telbank.rbac.web.openapi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.service.OpenApiQueryService;
import com.telbank.rbac.service.PermissionQueryService;
import com.telbank.rbac.service.SdkEndpointService;
import com.telbank.rbac.service.UserSystemService;
import com.telbank.rbac.web.dto.OpenApiDtos.ConnectionResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.EndpointsSyncRequest;
import com.telbank.rbac.web.dto.OpenApiDtos.EndpointsSyncResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.MappingsResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.PageActionsResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.PermissionItem;
import com.telbank.rbac.web.dto.OpenApiDtos.UserPermissionsResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.UserSystemItem;

import jakarta.validation.Valid;

/**
 * SDK 开放接口（契约 §4.2）。
 *
 * <p>本 Controller 不做 JWT 认证；{@code /openapi/**} 由 AppKey + HMAC-SHA256 过滤器统一验签
 * （{@code AppKeyAuthFilter}）。</p>
 */
@RestController
@RequestMapping("/openapi/sdk")
public class OpenApiSdkController {

    private final OpenApiQueryService openApiQueryService;
    private final PermissionQueryService permissionQueryService;
    private final UserSystemService userSystemService;
    private final SdkEndpointService sdkEndpointService;

    public OpenApiSdkController(OpenApiQueryService openApiQueryService,
                                PermissionQueryService permissionQueryService,
                                UserSystemService userSystemService,
                                SdkEndpointService sdkEndpointService) {
        this.openApiQueryService = openApiQueryService;
        this.permissionQueryService = permissionQueryService;
        this.userSystemService = userSystemService;
        this.sdkEndpointService = sdkEndpointService;
    }

    /** 映射表全量下发（method + api_pattern -> code）。 */
    @GetMapping("/mappings")
    public ApiResponse<MappingsResponse> mappings(@RequestParam String systemCode) {
        return ApiResponse.ok(openApiQueryService.mappings(systemCode));
    }

    /** 用户当前生效权限集（带 validFrom/validUntil + serverTime）。 */
    @GetMapping("/user-permissions")
    public ApiResponse<UserPermissionsResponse> userPermissions(
            @RequestParam String systemCode,
            @RequestParam String externalUserId) {
        SysSystem system = openApiQueryService.requireEnabledSystem(systemCode);
        List<PermissionItem> permissions = permissionQueryService.userPermissions(system, externalUserId);
        Instant serverTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return ApiResponse.ok(new UserPermissionsResponse(permissions, serverTime));
    }

    /** 用户可访问系统列表（SDK 的 /rbac/me/systems 依赖，契约 §4.2）。 */
    @GetMapping("/user-systems")
    public ApiResponse<List<UserSystemItem>> userSystems(@RequestParam String externalUserId) {
        return ApiResponse.ok(userSystemService.openApiUserSystems(externalUserId));
    }

    /** 页面操作元数据下发（模式 B）。 */
    @GetMapping("/page-actions")
    public ApiResponse<PageActionsResponse> pageActions(
            @RequestParam String systemCode,
            @RequestParam(required = false) String pageCode) {
        return ApiResponse.ok(openApiQueryService.pageActions(systemCode, pageCode));
    }

    /** 区域 Redis 连接信息（SDK 订阅变更通知）。 */
    @GetMapping("/connection")
    public ApiResponse<ConnectionResponse> connection(@RequestParam String systemCode) {
        return ApiResponse.ok(openApiQueryService.connection(systemCode));
    }

    /** SDK 全量端点上报（幂等 upsert）。 */
    @PostMapping("/endpoints/sync")
    public ApiResponse<EndpointsSyncResponse> syncEndpoints(
            @Valid @RequestBody EndpointsSyncRequest request) {
        return ApiResponse.ok(sdkEndpointService.sync(request));
    }
}
