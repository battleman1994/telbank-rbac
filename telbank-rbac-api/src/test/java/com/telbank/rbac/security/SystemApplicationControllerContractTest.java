package com.telbank.rbac.security;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.telbank.rbac.web.admin.SystemApplicationController;
import com.telbank.rbac.web.openapi.OpenApiOnboardingController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统接入上报/审核路由契约（A16）：上报走 openapi onboarding 验签；审核仅平台超管。
 */
class SystemApplicationControllerContractTest {

    @Test
    void reviewEndpointsArePlatformAdminOnly() {
        PreAuthorize preAuthorize = SystemApplicationController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("@perm.isPlatformAdmin()");

        RequestMapping mapping = SystemApplicationController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/system-applications");
    }

    @Test
    void reviewEndpointsExposePageApproveReject() throws NoSuchMethodException {
        Method page = SystemApplicationController.class.getMethod("page", String.class, Integer.class, Integer.class);
        assertThat(page.getAnnotation(GetMapping.class)).isNotNull();

        Method approve = SystemApplicationController.class.getMethod("approve", Long.class,
                com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveRequest.class);
        assertThat(approve.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/approve");

        Method reject = SystemApplicationController.class.getMethod("reject", Long.class,
                com.telbank.rbac.web.dto.SystemApplicationDtos.RejectRequest.class);
        assertThat(reject.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/reject");
    }

    @Test
    void onboardingReportUsesOpenApiRoute() throws NoSuchMethodException {
        RequestMapping mapping = OpenApiOnboardingController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/openapi/onboarding");

        Method report = OpenApiOnboardingController.class.getMethod("report",
                com.telbank.rbac.web.dto.OnboardingDtos.SystemReportRequest.class,
                OpenApiCaller.class);
        assertThat(report.getAnnotation(PostMapping.class).value()).containsExactly("/systems");
    }
}
