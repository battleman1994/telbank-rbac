package com.telbank.rbac.sdk.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import com.telbank.rbac.sdk.client.OnboardingApplyRequest;
import com.telbank.rbac.sdk.client.OnboardingApplyResult;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 系统接入自动上报器（A16）。
 *
 * <p>宿主启动完成后（{@link ApplicationReadyEvent}），若 {@code rbac-sdk.onboarding.enabled=true}
 * 且 {@code rbac-sdk.onboarding.report-on-startup=true}，使用 <b>onboarding 专用 AppKey/AppSecret</b>
 * 对 {@code POST /openapi/onboarding/systems} 做 AppKey + HMAC-SHA256 签名上报，body 为
 * {@code {systemCode,name,region,sdkVersion?,remark?}}。权限中心只创建待审核申请（PENDING），
 * 不直接开通系统；平台超管审核后完成接入。</p>
 *
 * <p>与 endpoint sync 完全独立：上报在守护线程中执行，失败仅 warning，绝不影响业务系统启动。</p>
 */
public class RbacOnboardingReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RbacOnboardingReporter.class);

    private static final String THREAD_NAME = "rbac-sdk-onboarding-reporter";

    /** 自动上报时写入的备注（权限中心申请列表展示用，可后续人工审核时忽略）。 */
    private static final String AUTO_REPORT_REMARK = "telbank-rbac SDK 启动自动上报";

    private final RbacSdkProperties properties;

    private final RbacOpenApiClient remoteClient;

    public RbacOnboardingReporter(RbacSdkProperties properties, RbacOpenApiClient remoteClient) {
        if (properties == null || remoteClient == null) {
            throw new IllegalArgumentException("RbacOnboardingReporter 依赖不能为空");
        }
        this.properties = properties;
        this.remoteClient = remoteClient;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        RbacSdkProperties.Onboarding onboarding = properties.getOnboarding();
        if (onboarding == null || !onboarding.isEnabled()) {
            log.debug("rbac-sdk.onboarding.enabled=false，跳过系统接入自动上报");
            return;
        }
        if (!onboarding.isReportOnStartup()) {
            log.info("rbac-sdk.onboarding.report-on-startup=false，跳过系统接入自动上报");
            return;
        }
        if (!isConfigured(onboarding)) {
            log.warn("rbac-sdk.onboarding.enabled=true 但缺少 onboarding app-key/app-secret，跳过系统接入自动上报");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                reportSafely();
            }
        }, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 执行一次上报（异常在此吞掉，只 warning）；包级可见便于单测直接调用。
     */
    void reportSafely() {
        try {
            OnboardingApplyRequest request = buildRequest();
            OnboardingApplyResult result = remoteClient.reportOnboarding(request);
            if (result == null) {
                log.info("系统接入申请已上报（权限中心未返回结果体）: systemCode={}", request.getSystemCode());
            } else {
                log.info("系统接入申请已自动上报，等待平台审核: systemCode={}, applicationId={}, status={}",
                        request.getSystemCode(), result.getApplicationId(), result.getStatus());
            }
        } catch (Throwable t) {
            // 自动上报是旁路能力：任何失败都不得影响业务系统启动/运行
            log.warn("系统接入自动上报失败（不影响业务启动）: {}", t.toString());
        }
    }

    /**
     * 构造上报请求；system-name 未配置时用 systemCode 兜底（见 {@link RbacSdkProperties#validate()}）。
     */
    OnboardingApplyRequest buildRequest() {
        String systemCode = properties.getSystemCode();
        String name = properties.getSystemName();
        if (name == null || name.trim().isEmpty()) {
            name = systemCode;
        }
        return new OnboardingApplyRequest(systemCode, name,
                properties.getRegion() == null ? "" : properties.getRegion(),
                properties.getAppVersion(), AUTO_REPORT_REMARK);
    }

    private static boolean isConfigured(RbacSdkProperties.Onboarding onboarding) {
        return isNotBlank(onboarding.getAppKey()) && isNotBlank(onboarding.getAppSecret());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
