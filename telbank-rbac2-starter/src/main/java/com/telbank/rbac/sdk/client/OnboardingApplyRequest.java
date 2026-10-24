package com.telbank.rbac.sdk.client;

/**
 * 系统接入申请上报请求体（A16）：{@code POST /openapi/onboarding/systems}。
 *
 * <p>JSON 形状：{@code {systemCode,name,region,sdkVersion?,remark?}}。权限中心只创建待审核申请，
 * 不直接开通系统；平台超管审核通过后才完成接入。</p>
 */
public class OnboardingApplyRequest {

    /** 业务系统编码（待申请接入的系统，systemCode 唯一）。 */
    private String systemCode;

    /** 系统名称；未配置 rbac-sdk.system-name 时 SDK 用 systemCode 兜底。 */
    private String name;

    /** 所属区域（决定区域 Redis 频道，如 region_a）。 */
    private String region;

    /** SDK 版本号（可选）。 */
    private String sdkVersion;

    /** 备注（可选）。 */
    private String remark;

    public OnboardingApplyRequest() {
    }

    public OnboardingApplyRequest(String systemCode, String name, String region, String sdkVersion,
            String remark) {
        this.systemCode = systemCode;
        this.name = name;
        this.region = region;
        this.sdkVersion = sdkVersion;
        this.remark = remark;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
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

    public String getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return "OnboardingApplyRequest{systemCode='" + systemCode + "', name='" + name
                + "', region='" + region + "', sdkVersion='" + sdkVersion + "', remark='" + remark + "'}";
    }
}
