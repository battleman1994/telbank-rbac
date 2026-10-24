package com.telbank.rbac.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 配置绑定：需求 §6.13 全部配置键必须可绑定（松散绑定）。
 */
class RbacSdkPropertiesTest {

    @Test
    void bindsAllDocumentedKeys() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("rbac-sdk.enabled", "true");
        source.put("rbac-sdk.system-code", "crm");
        source.put("rbac-sdk.platform-base-url", "https://rbac.example.com/");
        source.put("rbac-sdk.app-key", "crm");
        source.put("rbac-sdk.app-secret", "crm-dev-secret");
        source.put("rbac-sdk.app-version", "1.0.0");
        source.put("rbac-sdk.endpoint.base-path", "/custom-rbac/");
        source.put("rbac-sdk.scan.base-packages", "com.telbank.crm");
        source.put("rbac-sdk.scan.report-on-startup", "false");
        source.put("rbac-sdk.interceptor.unmatched-strategy", "deny");
        source.put("rbac-sdk.interceptor.exclude-paths[0]", "/actuator/**");
        source.put("rbac-sdk.interceptor.exclude-paths[1]", "/health");
        source.put("rbac-sdk.cache.fallback-poll-interval", "120s");
        source.put("rbac-sdk.code-generator.pattern", "{systemCode}:{resource}:{action}");
        source.put("rbac-sdk.code-generator.resource-from", "controller-class");
        source.put("rbac-sdk.code-generator.action-from", "http-method");

        RbacSdkProperties properties = binder(source).bind("rbac-sdk",
                Bindable.of(RbacSdkProperties.class)).get();
        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getSystemCode()).isEqualTo("crm");
        assertThat(properties.getPlatformBaseUrl()).isEqualTo("https://rbac.example.com");
        assertThat(properties.getAppKey()).isEqualTo("crm");
        assertThat(properties.getAppSecret()).isEqualTo("crm-dev-secret");
        assertThat(properties.getAppVersion()).isEqualTo("1.0.0");
        assertThat(properties.getEndpoint().getBasePath()).isEqualTo("/custom-rbac");
        assertThat(properties.getScan().getBasePackages()).containsExactly("com.telbank.crm");
        assertThat(properties.getScan().isReportOnStartup()).isFalse();
        assertThat(properties.getInterceptor().getUnmatchedStrategy()).isEqualTo("deny");
        assertThat(properties.getInterceptor().isDenyUnmatched()).isTrue();
        assertThat(properties.getInterceptor().getExcludePaths())
                .containsExactly("/actuator/**", "/health");
        assertThat(properties.getCache().getFallbackPollInterval()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.getCache().getFallbackPollIntervalMillis()).isEqualTo(120_000L);
        assertThat(properties.getCodeGenerator().getPattern())
                .isEqualTo("{systemCode}:{resource}:{action}");
        assertThat(properties.getCodeGenerator().getResourceFrom()).isEqualTo("controller-class");
        assertThat(properties.getCodeGenerator().getActionFrom()).isEqualTo("http-method");
    }

    @Test
    void appliesDocumentedDefaults() {
        RbacSdkProperties properties = new RbacSdkProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getEndpoint().getBasePath()).isEqualTo("/rbac");
        assertThat(properties.getScan().isReportOnStartup()).isTrue();
        assertThat(properties.getScan().getBasePackages()).isEmpty();
        assertThat(properties.getInterceptor().isDenyUnmatched()).isFalse();
        assertThat(properties.getInterceptor().getExcludePaths())
                .containsExactly("/actuator/**", "/error", "/favicon.ico");
        assertThat(properties.getCache().getFallbackPollIntervalMillis()).isEqualTo(300_000L);
        assertThat(properties.getCodeGenerator().getPattern())
                .isEqualTo("{systemCode}:{resource}:{action}");
    }

    @Test
    void validateFailsFastWhenRequiredKeysMissing() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rbac-sdk.system-code");
    }

    @Test
    void validateRejectsNonHttpBaseUrl() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("secret");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform-base-url");
    }

    @Test
    void validateNormalizesBasePath() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("https://rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("secret");
        properties.getEndpoint().setBasePath("rbac/");
        properties.validate();
        assertThat(properties.getEndpoint().getBasePath()).isEqualTo("/rbac");
    }

    private static Binder binder(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source));
    }
}
