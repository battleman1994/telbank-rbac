package com.telbank.rbac.sdk.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户权限集回源响应，来自 {@code GET /openapi/sdk/user-permissions} 的 data：
 * {@code {"permissions":[...],"serverTime":"2026-10-24T05:00:00Z"}}。
 *
 * <p>平台超管的 permissions 为 {@code [{"code":"*","validFrom":null,"validUntil":null}]}。</p>
 */
public class UserPermissionResponse {

    private List<UserPermissionEntry> permissions = new ArrayList<UserPermissionEntry>();

    /** 权限中心服务器时间（ISO-8601 UTC），用于计算 SDK 时钟偏移。 */
    private String serverTime;

    public UserPermissionResponse() {
    }

    public List<UserPermissionEntry> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<UserPermissionEntry> permissions) {
        this.permissions = permissions;
    }

    public String getServerTime() {
        return serverTime;
    }

    public void setServerTime(String serverTime) {
        this.serverTime = serverTime;
    }
}
