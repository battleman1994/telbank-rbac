package com.telbank.rbac.sdk.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.client.OnboardingApplyRequest;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * 系统接入自动上报（A16）：启动触发、enabled/report-on-startup 开关、失败不抛、请求体字段。
 */
class RbacOnboardingReporterTest {

    @Test
    void disabledDoesNotReport() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacSdkProperties properties = properties();
        properties.getOnboarding().setEnabled(false);

        new RbacOnboardingReporter(properties, remote).onApplicationEvent(null);
        Thread.sleep(150L);

        assertThat(remote.onboardingCalls.get()).isZero();
    }

    @Test
    void reportOnStartupFalseDoesNotReport() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacSdkProperties properties = properties();
        properties.getOnboarding().setReportOnStartup(false);

        new RbacOnboardingReporter(properties, remote).onApplicationEvent(null);
        Thread.sleep(150L);

        assertThat(remote.onboardingCalls.get()).isZero();
    }

    @Test
    void missingOnboardingCredentialsSkipsWithoutException() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacSdkProperties properties = properties();
        properties.getOnboarding().setAppKey(null);
        properties.getOnboarding().setAppSecret(null);

        assertThatCode(() -> new RbacOnboardingReporter(properties, remote)
                .onApplicationEvent(null)).doesNotThrowAnyException();
        Thread.sleep(150L);

        assertThat(remote.onboardingCalls.get()).isZero();
    }

    @Test
    void applicationReadyTriggersReportWithConfiguredBody() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacSdkProperties properties = properties();

        new RbacOnboardingReporter(properties, remote).onApplicationEvent(null);
        waitForCalls(remote, 1);

        assertThat(remote.onboardingCalls.get()).isEqualTo(1);
        OnboardingApplyRequest request = remote.lastOnboardingRequest;
        assertThat(request.getSystemCode()).isEqualTo("demo-sdk");
        assertThat(request.getName()).isEqualTo("Demo SDK 系统");
        assertThat(request.getRegion()).isEqualTo("region_a");
        assertThat(request.getSdkVersion()).isEqualTo("1.0.0");
        assertThat(request.getRemark()).isNotBlank();
    }

    @Test
    void systemNameFallbackToSystemCode() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacSdkProperties properties = properties();
        properties.setSystemName("   ");
        properties.validate();

        assertThat(new RbacOnboardingReporter(properties, remote).buildRequest().getName())
                .isEqualTo("demo-sdk");
    }

    @Test
    void reportFailureOnlyWarnsAndDoesNotThrow() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnOnboarding = new RbacRemoteException("onboarding endpoint down");
        RbacOnboardingReporter reporter = new RbacOnboardingReporter(properties(), remote);

        assertThatCode(reporter::reportSafely).doesNotThrowAnyException();
        assertThat(remote.onboardingCalls.get()).isEqualTo(1);
    }

    private static void waitForCalls(StubRbacOpenApiClient remote, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline
                && remote.onboardingCalls.get() < expected) {
            Thread.sleep(20L);
        }
    }

    private static RbacSdkProperties properties() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("demo-sdk");
        properties.setPlatformBaseUrl("https://rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("crm-dev-secret");
        properties.setSystemName("Demo SDK 系统");
        properties.setRegion("region_a");
        properties.setAppVersion("1.0.0");
        properties.getOnboarding().setEnabled(true);
        properties.getOnboarding().setAppKey("onboarding-app");
        properties.getOnboarding().setAppSecret("onboarding-secret");
        properties.validate();
        return properties;
    }
}
