package com.telbank.rbac.sdk.client;

import java.util.List;

/**
 * SDK 内部使用的权限中心开放接口客户端（非公开扩展点）。
 *
 * <p>与公开的 {@link RbacClient} 分离：业务方可以替换 {@link RbacClient}，但 SDK 自身的
 * 映射/权限集/pageActions 回源与端点上报不能被替换，否则整套校验逻辑不完整。</p>
 *
 * <p>所有方法在失败时抛出 {@link RbacRemoteException}（非受检），由调用方决定降级。</p>
 */
public interface RbacOpenApiClient {

    /**
     * 拉取全量接口映射：{@code GET /openapi/sdk/mappings?systemCode=}。
     */
    List<MappingEntry> fetchMappings();

    /**
     * 回源用户权限集：{@code GET /openapi/sdk/user-permissions?systemCode=&externalUserId=}。
     */
    UserPermissionResponse fetchUserPermissions(String externalUserId);

    /**
     * 拉取全量页面操作元数据：{@code GET /openapi/sdk/page-actions?systemCode=}（不传 pageCode）。
     */
    List<PageAction> fetchPageActions();

    /**
     * 获取本区域 Redis 连接信息：{@code GET /openapi/sdk/connection?systemCode=}。
     */
    ConnectionInfo fetchConnection();

    /**
     * 全量上报端点：{@code POST /openapi/sdk/endpoints/sync}。
     */
    void syncEndpoints(List<EndpointInfo> endpoints);

    /**
     * 查询用户有有效角色的业务系统：{@code GET /openapi/sdk/user-systems?externalUserId=}。
     *
     * <p>响应字段 code/name/region/isDefault 结构不变；系统由权限中心按有效角色推导
     * （排除 platform 平台系统），用户无有效角色时返回空列表。</p>
     */
    List<SystemInfo> fetchUserSystems(String externalUserId);
}
