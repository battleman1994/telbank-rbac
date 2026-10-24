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
    public static final int IS_DEFAULT_YES = 1;

    // ------------------------------------------------------------------
    // Redis 变更通知（契约 §6）
    // ------------------------------------------------------------------
    public static final String CHANGE_TYPE_PERMISSION = "PERMISSION_CHANGED";
    public static final String CHANGE_TYPE_PAGE_ACTION = "PAGE_ACTION_CHANGED";
    public static final String CHANGE_TYPE_ENDPOINT = "ENDPOINT_CHANGED";

    // ------------------------------------------------------------------
    // 权限码
    // ------------------------------------------------------------------
    public static final String PLATFORM_ADMIN_PERMISSION_CODE = "*";

    // ------------------------------------------------------------------
    // 管理端自身权限码（契约 §3）
    // ------------------------------------------------------------------
    public static final String PERM_SYSTEM_MANAGE = "rbac:system:manage";
    public static final String PERM_ROLE_MANAGE = "rbac:role:manage";
    public static final String PERM_PERMISSION_MANAGE = "rbac:permission:manage";
    public static final String PERM_PAGE_MANAGE = "rbac:page:manage";
    public static final String PERM_AUDIT_VIEW = "rbac:audit:view";

    // ------------------------------------------------------------------
    // 页面操作 action 枚举（契约 §3.8）
    // ------------------------------------------------------------------
    public static final java.util.Set<String> PAGE_ACTION_TYPES = java.util.Set.of(
            "openDialog", "callApi", "download", "navigate", "confirmThenCallApi");
}
