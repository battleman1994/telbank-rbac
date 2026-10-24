package com.telbank.rbac.sdk.client;

import java.util.List;
import java.util.Set;

/**
 * SDK 对外提供的编程式权限查询 API（需求 §6.5），供业务系统后端可选注入使用。
 *
 * <p>这是 SDK 唯一允许业务方替换的扩展点（声明同类型 Bean 即可覆盖默认实现），
 * 不是权限校验的扩展点：拦截器、缓存、变更订阅始终由 SDK 内部实现。</p>
 *
 * <p>SDK 以 {@code rbac-sdk.system-code} 为单位接入权限中心，因此带 systemCode 入参的方法
 * 只接受本系统编码，传其他系统会抛出 {@link IllegalArgumentException}（AppKey 与系统绑定，
 * 跨系统查询会被权限中心拒绝）。</p>
 */
public interface RbacClient {

    /**
     * 查询用户有有效角色的业务系统列表（来自 {@code GET /openapi/sdk/user-systems}）。
     *
     * <p>v2.0 系统隔离模型下，系统访问由有效 user_role 推导（权限中心排除 platform 平台系统），
     * SDK 不缓存、不推导、原样透传；返回空列表表示用户当前没有任何有效角色，属于正常结果。</p>
     *
     * <p>仅当权限中心不可用（回源失败）时降级为「当前配置系统」单例，保证业务页面可用。</p>
     *
     * @param externalUserId 外部用户 ID（JWT sub）
     * @return 系统列表，不返回 null
     */
    List<SystemInfo> getSystems(String externalUserId);

    /**
     * 查询用户在指定系统下的有效权限码集合（权限中心不可用且无旧缓存时抛出
     * {@link RbacRemoteException}；有旧缓存时返回旧缓存中当前有效的权限码）。
     *
     * @param externalUserId 外部用户 ID
     * @param systemCode     业务系统编码，必须等于 {@code rbac-sdk.system-code}
     * @return 有效权限码集合，平台超管返回 {@code ["*"]}，不返回 null
     */
    Set<String> getPermissions(String externalUserId, String systemCode);

    /**
     * 查询指定页面的操作元数据（模式 B）。
     *
     * @param systemCode 业务系统编码，必须等于 {@code rbac-sdk.system-code}
     * @param pageCode   页面编码；为空时返回全部
     * @return 页面操作列表，不返回 null
     */
    List<PageAction> getPageActions(String systemCode, String pageCode);

    /**
     * 判断用户是否为平台超管（权限中心对超管返回权限码 {@code *}）。
     *
     * <p>权限中心不可用且无旧缓存时返回 false。</p>
     *
     * @param externalUserId 外部用户 ID
     * @return 是否平台超管
     */
    boolean isPlatformAdmin(String externalUserId);
}
