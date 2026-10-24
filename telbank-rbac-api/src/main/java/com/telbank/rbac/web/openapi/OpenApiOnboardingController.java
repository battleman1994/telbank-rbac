package com.telbank.rbac.web.openapi;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.security.OpenApiCaller;
import com.telbank.rbac.service.SystemApplicationService;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportRequest;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportResponse;

import jakarta.validation.Valid;

/**
 * SDK 系统接入上报（A16）。使用 {@code rbac.onboarding} 独立 AppKey/HMAC 验签，
 * 由 {@code AppKeyAuthFilter} 统一处理；上报只写申请记录，不直接创建系统。
 */
@RestController
@RequestMapping("/openapi/onboarding")
public class OpenApiOnboardingController {

    private final SystemApplicationService systemApplicationService;

    public OpenApiOnboardingController(SystemApplicationService systemApplicationService) {
        this.systemApplicationService = systemApplicationService;
    }

    @PostMapping("/systems")
    public ApiResponse<SystemReportResponse> report(
            @Valid @RequestBody SystemReportRequest request,
            @RequestAttribute(name = RbacConstants.ATTR_OPENAPI_CALLER, required = false)
            OpenApiCaller caller) {
        return ApiResponse.ok(systemApplicationService.report(request,
                caller == null ? null : caller.appKey()));
    }
}
