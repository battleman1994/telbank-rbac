package com.telbank.rbac.sdk.client;

import java.util.List;


import feign.Headers;
import feign.Param;
import feign.RequestLine;

/**
 * 权限中心开放接口的声明式 Feign 客户端（JDK 8 版 SDK）。
 *
 * <p>一个接口类对应一组 API：方法签名即协议（HTTP 方法、path、query、请求体），
 * 业务层不再手工拼 URL / 构造 Request / 处理字节流。签名头由基础设施
 * {@link RbacSigningClient} 统一注入，响应由 Feign 的 Jackson 解码器反序列化为
 * {@link RbacEnvelope} 泛型 POJO 后由 {@link HttpRbacOpenApiClient} 取 {@code data}。</p>
 *
 * <p>与 JDK 21 版 SDK 的同名接口保持一致（仅注解所在包相同、底层 JSON 库版本不同）。</p>
 */
interface RbacOpenApiFeignApi {

    /** GET /openapi/sdk/mappings?systemCode= */
    @RequestLine("GET /openapi/sdk/mappings?systemCode={systemCode}")
    RbacEnvelope<MappingsResponse> fetchMappings(@Param("systemCode") String systemCode);

    /** GET /openapi/sdk/user-permissions?externalUserId=&systemCode= */
    @RequestLine("GET /openapi/sdk/user-permissions?externalUserId={externalUserId}&systemCode={systemCode}")
    RbacEnvelope<UserPermissionResponse> fetchUserPermissions(
            @Param("externalUserId") String externalUserId, @Param("systemCode") String systemCode);

    /** GET /openapi/sdk/page-actions?systemCode= */
    @RequestLine("GET /openapi/sdk/page-actions?systemCode={systemCode}")
    RbacEnvelope<PageActionsResponse> fetchPageActions(@Param("systemCode") String systemCode);

    /** GET /openapi/sdk/connection?systemCode= */
    @RequestLine("GET /openapi/sdk/connection?systemCode={systemCode}")
    RbacEnvelope<ConnectionInfo> fetchConnection(@Param("systemCode") String systemCode);

    /** POST /openapi/sdk/endpoints/sync（全量上报，成功不关心响应体） */
    @RequestLine("POST /openapi/sdk/endpoints/sync")
    @Headers("Content-Type: application/json")
    void syncEndpoints(EndpointSyncRequest request);

    /** GET /openapi/sdk/user-systems?externalUserId= */
    @RequestLine("GET /openapi/sdk/user-systems?externalUserId={externalUserId}")
    RbacEnvelope<List<SystemInfo>> fetchUserSystems(@Param("externalUserId") String externalUserId);
}
