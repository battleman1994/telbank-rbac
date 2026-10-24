package com.telbank.rbac.common;

import java.time.Duration;

/**
 * 权限中心内部常量。
 */
public final class RbacConstants {

    private RbacConstants() {
    }

    // ------------------------------------------------------------------
    // 请求头（契约 §1.5 / §4.1）
    // ------------------------------------------------------------------
    public static final String HEADER_SYSTEM_CODE = "X-System-Code";
    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    /** 签名时间戳允许窗口：±5 分钟。 */
    public static final long SIGNATURE_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    /** Nonce 防重放有效期：5 分钟。 */
    public static final Duration NONCE_TTL = Duration.ofMinutes(5);

    public static final String OPENAPI_PATH_PREFIX = "/openapi/";
    public static final String OPENAPI_APPROVAL_PATH_PREFIX = "/openapi/approval/";
    public static final String OPENAPI_ONBOARDING_PATH_PREFIX = "/openapi/onboarding/";
    public static final String ATTR_OPENAPI_CALLER = "rbac.openapi.caller";

    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";

    // ------------------------------------------------------------------
    // 数据状态
    // ------------------------------------------------------------------
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;
    public static final int AUTO_REVOKE_YES = 1;
    public static final int IS_PUBLIC_YES = 1;

    // 系统接入申请状态（sys_system_application.status）
    public static final int APPLICATION_STATUS_PENDING = 0;
    public static final int APPLICATION_STATUS_APPROVED = 1;
    public static final int APPLICATION_STATUS_REJECTED = 2;
    public static final String APPLICATION_SOURCE_SDK = "SDK";

    // ------------------------------------------------------------------
    // Redis 变更通知（契约 §6）
    // ------------------------------------------------------------------
    public static final String CHANGE_TYPE_PERMISSION = "PERMISSION_CHANGED";
    public static final String CHANGE_TYPE_PAGE_ACTION = "PAGE_ACTION_CHANGED";
    public static final String CHANGE_TYPE_ENDPOINT = "ENDPOINT_CHANGED";

    // ------------------------------------------------------------------
    // 权限码
    // ------------------------------------------------------------------
    /** 通配权限码：匹配任意权限（A 方案）。 */
    public static final String PERM_WILDCARD = "*";

    /** 平台超管在 /api/me、bootstrap 中返回的权限码（通配）。 */
    public static final String PLATFORM_ADMIN_PERMISSION_CODE = PERM_WILDCARD;

    // ------------------------------------------------------------------
    // 管理端权限码（系统隔离模型：各系统内短码，契约 §3）
    // ------------------------------------------------------------------
    /**
     * @deprecated A 方案（05 迁移）已用 {@link #PERM_WILDCARD} 取代平台系统内的
     *             {@code platform:admin}，保留常量仅为兼容旧代码/数据排查。
     */
    @Deprecated
    public static final String PERM_PLATFORM_ADMIN = "platform:admin";

    public static final String PERM_ROLE_MANAGE = "role:manage";
    public static final String PERM_PERMISSION_MANAGE = "permission:manage";
    public static final String PERM_RESOURCE_MANAGE = "resource:manage";
    public static final String PERM_PAGE_MANAGE = "page:manage";
    public static final String PERM_USER_MANAGE = "user:manage";
    public static final String PERM_AUDIT_VIEW = "audit:view";

    // ------------------------------------------------------------------
    // 页面操作 action 枚举（契约 §3.8）
    // ------------------------------------------------------------------
    public static final java.util.Set<String> PAGE_ACTION_TYPES = java.util.Set.of(
            "openDialog", "callApi", "download", "navigate", "confirmThenCallApi");
}
