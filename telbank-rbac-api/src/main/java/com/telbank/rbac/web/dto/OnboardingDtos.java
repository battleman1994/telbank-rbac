package com.telbank.rbac.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SDK 系统接入上报 DTO（A16，openapi，onboarding AppKey 验签）。
 */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    /** POST /openapi/onboarding/systems：SDK 启动时上报本系统信息。 */
    public record SystemReportRequest(@NotBlank @Size(max = 64) String systemCode,
                                      @NotBlank @Size(max = 100) String name,
                                      @NotBlank @Size(max = 32) String region,
                                      @Size(max = 32) String sdkVersion,
                                      @Size(max = 255) String remark) {
    }

    /** 上报响应：applicationId + 当前申请状态（PENDING/APPROVED）。 */
    public record SystemReportResponse(Long applicationId, String status) {
    }
}
