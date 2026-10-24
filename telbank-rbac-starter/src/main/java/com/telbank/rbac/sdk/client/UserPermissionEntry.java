package com.telbank.rbac.sdk.client;

/**
 * 用户权限条目（带时效），来自 {@code GET /openapi/sdk/user-permissions} 的 data.permissions：
 * {@code {"code":"order:delete","validFrom":null,"validUntil":"2026-10-31T23:59:59Z"}}。
 *
 * <p>时间字段保持 ISO-8601 UTC 原始字符串，由 SDK 缓存层解析为 epoch millis 并参与
 * 「本地时间 + 时钟偏移」的时效判定。</p>
 */
public class UserPermissionEntry {

    private String code;

    private String validFrom;

    private String validUntil;

    public UserPermissionEntry() {
    }

    public UserPermissionEntry(String code, String validFrom, String validUntil) {
        this.code = code;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(String validFrom) {
        this.validFrom = validFrom;
    }

    public String getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(String validUntil) {
        this.validUntil = validUntil;
    }

    @Override
    public String toString() {
        return "UserPermissionEntry{code='" + code + "', validFrom='" + validFrom
                + "', validUntil='" + validUntil + "'}";
    }
}
