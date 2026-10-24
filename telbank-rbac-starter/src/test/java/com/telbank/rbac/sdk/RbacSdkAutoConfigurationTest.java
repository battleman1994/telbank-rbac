package com.telbank.rbac.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacMappingCache;
import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.RbacClient;
import com.telbank.rbac.sdk.client.RbacSigner;
import com.telbank.rbac.sdk.filter.RbacPermissionFilter;
import com.telbank.rbac.sdk.notify.RbacChangeSubscriber;
import com.telbank.rbac.sdk.notify.RbacPollingFallback;
import com.telbank.rbac.sdk.scan.RbacEndpointScanner;
import com.telbank.rbac.sdk.web.RbacController;

import java.util.List;
import java.util.Set;

/**
 * 总开关与组件注册（契约 §7.1/§7.5）：enabled=false 全不注册；enabled=true 注册整套；
 * 仅 RbacClient 允许业务方同类型 Bean 覆盖。
 */
class RbacSdkAutoConfigurationTest {

    private static final String[] ENABLED_PROPERTIES = {
            "rbac-sdk.enabled=true",
            "rbac-sdk.system-code=crm",
            "rbac-sdk.platform-base-url=https://rbac.example.com",
            "rbac-sdk.app-key=crm",
            "rbac-sdk.app-secret=crm-dev-secret"};

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RbacSdkAutoConfiguration.class));

    @Test
    void disabledByDefaultRegistersNothing() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RbacSigner.class);
            assertThat(context).doesNotHaveBean(RbacMappingCache.class);
            assertThat(context).doesNotHaveBean(RbacUserPermissionCache.class);
            assertThat(context).doesNotHaveBean(RbacClient.class);
            assertThat(context).doesNotHaveBean(RbacController.class);
            assertThat(context).doesNotHaveBean(RbacPermissionFilter.class);
            assertThat(context).doesNotHaveBean(RbacEndpointScanner.class);
            assertThat(context).doesNotHaveBean(RbacPollingFallback.class);
            assertThat(context).doesNotHaveBean(RbacChangeSubscriber.class);
        });
    }

    @Test
    void enabledRegistersFullSdk() {
        runner.withPropertyValues(ENABLED_PROPERTIES).run(context -> {
            assertThat(context).hasSingleBean(RbacSigner.class);
            assertThat(context).hasSingleBean(RbacMappingCache.class);
            assertThat(context).hasSingleBean(RbacPageActionCache.class);
            assertThat(context).hasSingleBean(RbacUserPermissionCache.class);
            assertThat(context).hasSingleBean(RbacCacheRefresher.class);
            assertThat(context).hasSingleBean(RbacPollingFallback.class);
            assertThat(context).hasSingleBean(RbacClient.class);
            assertThat(context).hasSingleBean(RbacController.class);
            assertThat(context).hasSingleBean(RbacEndpointScanner.class);
            assertThat(context).hasSingleBean(RbacChangeSubscriber.class);

            FilterRegistrationBean<?> registration = context.getBean(
                    "rbacPermissionFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(RbacPermissionFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 100);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    void customRbacClientOverridesDefaultImplementation() {
        runner.withPropertyValues(ENABLED_PROPERTIES)
                .withBean(RbacClient.class, CustomRbacClient::new)
                .run(context -> assertThat(context.getBean(RbacClient.class))
                        .isInstanceOf(CustomRbacClient.class));
    }

    @Test
    void invalidConfigurationFailsFast() {
        runner.withPropertyValues("rbac-sdk.enabled=true",
                        "rbac-sdk.platform-base-url=https://rbac.example.com",
                        "rbac-sdk.app-key=crm",
                        "rbac-sdk.app-secret=crm-dev-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("rbac-sdk.system-code");
                });
    }

    /**
     * 业务方自定义 RbacClient（覆盖点验证用）。
     */
    static class CustomRbacClient implements RbacClient {

        @Override
        public List<com.telbank.rbac.sdk.client.SystemInfo> getSystems(String externalUserId) {
            return java.util.Collections.emptyList();
        }

        @Override
        public Set<String> getPermissions(String externalUserId, String systemCode) {
            return java.util.Collections.emptySet();
        }

        @Override
        public List<com.telbank.rbac.sdk.client.PageAction> getPageActions(String systemCode,
                String pageCode) {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isPlatformAdmin(String externalUserId) {
            return false;
        }
    }
}
