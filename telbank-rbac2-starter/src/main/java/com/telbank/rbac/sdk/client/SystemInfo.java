package com.telbank.rbac.sdk.client;

/**
 * 业务系统信息（{@code /rbac/me/systems}、{@link RbacClient#getSystems(String)}）。
 *
 * <p>对应开放接口 {@code GET /openapi/sdk/user-systems} 的 data 元素：
 * {@code {"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}}</p>
 */
public class SystemInfo {

    private String code;

    private String name;

    private String region;

    private Boolean isDefault;

    public SystemInfo() {
    }

    public SystemInfo(String code, String name, String region, Boolean isDefault) {
        this.code = code;
        this.name = name;
        this.region = region;
        this.isDefault = isDefault;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * 注意：getter 必须叫 getIsDefault，Jackson 才会序列化成 {@code isDefault}（而不是 {@code default}）。
     */
    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    @Override
    public String toString() {
        return "SystemInfo{code='" + code + "', name='" + name + "', region='" + region
                + "', isDefault=" + isDefault + '}';
    }
}
