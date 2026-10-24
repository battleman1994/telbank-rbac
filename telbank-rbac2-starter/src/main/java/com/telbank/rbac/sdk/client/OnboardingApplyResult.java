package com.telbank.rbac.sdk.client;

/**
 * 系统接入申请上报响应（A16）：{@code {applicationId,status}}。
 *
 * <p>SDK 只做启动上报，不轮询审核结果；{@code status} 通常为 {@code PENDING}（待平台超管审核）。</p>
 */
public class OnboardingApplyResult {

    /** 接入申请 ID（权限中心生成）。 */
    private Long applicationId;

    /** 申请状态，如 PENDING。 */
    private String status;

    public OnboardingApplyResult() {
    }

    public OnboardingApplyResult(Long applicationId, String status) {
        this.applicationId = applicationId;
        this.status = status;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "OnboardingApplyResult{applicationId=" + applicationId + ", status='" + status + "'}";
    }
}
