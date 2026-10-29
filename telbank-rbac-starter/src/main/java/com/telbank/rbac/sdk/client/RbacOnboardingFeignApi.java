package com.telbank.rbac.sdk.client;


import feign.Headers;
import feign.RequestLine;

/**
 * 系统接入上报的声明式 Feign 客户端（使用 onboarding 专用 AppKey/AppSecret 签名）。
 *
 * <p>与业务系统主 AppKey 隔离：独立 Feign 代理 + 独立 {@link RbacSigningClient}。</p>
 */
interface RbacOnboardingFeignApi {

    /** POST /openapi/onboarding/systems */
    @RequestLine("POST /openapi/onboarding/systems")
    @Headers("Content-Type: application/json")
    RbacEnvelope<OnboardingApplyResult> reportOnboarding(OnboardingApplyRequest request);
}
