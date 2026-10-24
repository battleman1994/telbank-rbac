package com.telbank.rbac.sdk.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SDK 配置项，与需求文档 §6.13 一一对应。
 *
 * <pre>
 * rbac-sdk:
 *   enabled: true
 *   system-code: crm
 *   platform-base-url: https://rbac.example.com
 *   app-key: crm
 *   app-secret: ${RBAC_SDK_APP_SECRET}
 *   app-version: 1.0.0
 *   system-name: CRM系统              # 默认空 -> 使用 system-code
 *   region: region_a                 # 默认空字符串
 *   onboarding:                      # A16 系统接入自动上报（可选）
 *     enabled: false
 *     app-key: onboarding-app        # onboarding 专用 AppKey
 *     app-secret: ${RBAC_SDK_ONBOARDING_SECRET}
 *     report-on-startup: true
 *   endpoint:
 *     base-path: /rbac
 *   scan:
 *     base-packages: com.telbank.crm
 *     report-on-startup: true
 *   interceptor:
 *     unmatched-strategy: pass
 *     exclude-paths: [/actuator/**, /error, /favicon.ico]
 *   cache:
 *     fallback-poll-interval: 300s
 *   code-generator:
 *     pattern: "{systemCode}:{resource}:{action}"
 *     resource-from: controller-class
 *     action-from: http-method
 * </pre>
 *
 * 使用松散绑定（relaxed binding），{@code system-code}/{@code systemCode} 均可。
 */
@ConfigurationProperties(prefix = "rbac-sdk")
public class RbacSdkProperties {

    /** 总开关；配合 {@code @ConditionalOnProperty} 使用，默认关闭。 */
    private boolean enabled = false;

    /** 业务系统编码，权限中心侧唯一。 */
    private String systemCode;

    /** 权限中心地址（HTTPS），例如 https://rbac.example.com。 */
    private String platformBaseUrl;

    /** 权限中心分配给业务系统的 AppKey。 */
    private String appKey;

    /** 权限中心分配给业务系统的 AppSecret，建议通过环境变量注入。 */
    private String appSecret;

    /** SDK 版本号，仅用于日志/User-Agent 标识。 */
    private String appVersion;

    /** 系统名称；未配置（空）时默认使用 systemCode（系统接入自动上报时提交）。 */
    private String systemName;

    /** 所属区域（系统接入自动上报时提交，如 region_a）；未配置默认空字符串。 */
    private String region;

    /** 系统接入自动上报（A16，可选能力）。 */
    private final Onboarding onboarding = new Onboarding();

    private final Endpoint endpoint = new Endpoint();

    private final Scan scan = new Scan();

    private final Interceptor interceptor = new Interceptor();

    private final Cache cache = new Cache();

    private final CodeGenerator codeGenerator = new CodeGenerator();

    /**
     * 启动期校验必填项并规范化路径类配置；配置非法时快速失败（避免“已开启但静默放行”）。
     */
    public void validate() {
        systemCode = requireText(systemCode, "rbac-sdk.system-code");
        platformBaseUrl = requireText(platformBaseUrl, "rbac-sdk.platform-base-url");
        appKey = requireText(appKey, "rbac-sdk.app-key");
        appSecret = requireText(appSecret, "rbac-sdk.app-secret");
        if (!platformBaseUrl.startsWith("http://") && !platformBaseUrl.startsWith("https://")) {
            throw new IllegalStateException("rbac-sdk.platform-base-url 必须以 http:// 或 https:// 开头，当前值: "
                    + platformBaseUrl);
        }
        while (platformBaseUrl.endsWith("/")) {
            platformBaseUrl = platformBaseUrl.substring(0, platformBaseUrl.length() - 1);
        }
        appVersion = appVersion == null ? "" : appVersion.trim();
        // system-name 未配置时用 systemCode 兜底（系统接入自动上报 body.name）
        systemName = systemName == null ? "" : systemName.trim();
        if (systemName.isEmpty()) {
            systemName = systemCode;
        }
        region = region == null ? "" : region.trim();
        if (onboarding.getAppKey() != null) {
            onboarding.setAppKey(onboarding.getAppKey().trim());
        }
        if (onboarding.getAppSecret() != null) {
            onboarding.setAppSecret(onboarding.getAppSecret().trim());
        }

        String basePath = endpoint.getBasePath();
        if (basePath == null || basePath.trim().isEmpty()) {
            basePath = "/rbac";
        }
        basePath = basePath.trim();
        if (!basePath.startsWith("/")) {
            basePath = "/" + basePath;
        }
        while (basePath.length() > 1 && basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        endpoint.setBasePath(basePath);

        if (scan.getBasePackages() == null) {
            scan.setBasePackages(new ArrayList<String>());
        }
        if (interceptor.getExcludePaths() == null) {
            interceptor.setExcludePaths(new ArrayList<String>(
                    Arrays.asList("/actuator/**", "/error", "/favicon.ico")));
        }
        if (cache.getFallbackPollInterval() == null || cache.getFallbackPollInterval().isNegative()
                || cache.getFallbackPollInterval().isZero()) {
            cache.setFallbackPollInterval(Duration.ofSeconds(300));
        }
        if (codeGenerator.getPattern() == null || codeGenerator.getPattern().trim().isEmpty()) {
            codeGenerator.setPattern("{systemCode}:{resource}:{action}");
        }
        if (codeGenerator.getResourceFrom() == null || codeGenerator.getResourceFrom().trim().isEmpty()) {
            codeGenerator.setResourceFrom("controller-class");
        }
        if (codeGenerator.getActionFrom() == null || codeGenerator.getActionFrom().trim().isEmpty()) {
            codeGenerator.setActionFrom("http-method");
        }
    }

    private static String requireText(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("rbac-sdk.enabled=true 时必须配置 " + key);
        }
        return value.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public String getPlatformBaseUrl() {
        return platformBaseUrl;
    }

    public void setPlatformBaseUrl(String platformBaseUrl) {
        this.platformBaseUrl = platformBaseUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Onboarding getOnboarding() {
        return onboarding;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public Scan getScan() {
        return scan;
    }

    public Interceptor getInterceptor() {
        return interceptor;
    }

    public Cache getCache() {
        return cache;
    }

    public CodeGenerator getCodeGenerator() {
        return codeGenerator;
    }

    /**
     * 系统接入自动上报（A16，可选）：enabled=true 时才需要 app-key/app-secret；配置不完整时
     * 启动上报器只打 warning 并跳过，不影响业务系统启动。
     */
    public static class Onboarding {

        /** 是否启用启动时自动上报系统接入申请，默认关闭。 */
        private boolean enabled = false;

        /** onboarding 专用 AppKey（由权限中心分配，与业务系统 AppKey 不同）。 */
        private String appKey;

        /** onboarding 专用 AppSecret，建议通过环境变量注入。 */
        private String appSecret;

        /** 是否在应用启动完成（ApplicationReadyEvent）后上报，默认 true。 */
        private boolean reportOnStartup = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public boolean isReportOnStartup() {
            return reportOnStartup;
        }

        public void setReportOnStartup(boolean reportOnStartup) {
            this.reportOnStartup = reportOnStartup;
        }
    }

    /** 自动注册的 {@code /rbac/me/*} 前缀。 */
    public static class Endpoint {

        private String basePath = "/rbac";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    /** 端点扫描与上报。 */
    public static class Scan {

        /** 只扫描（上报）这些包下的 Controller；为空表示不限制。 */
        private List<String> basePackages = new ArrayList<String>();

        /** 启动后是否上报全量端点。 */
        private boolean reportOnStartup = true;

        public List<String> getBasePackages() {
            return basePackages;
        }

        public void setBasePackages(List<String> basePackages) {
            this.basePackages = basePackages;
        }

        public boolean isReportOnStartup() {
            return reportOnStartup;
        }

        public void setReportOnStartup(boolean reportOnStartup) {
            this.reportOnStartup = reportOnStartup;
        }
    }

    /** 拦截器配置。 */
    public static class Interceptor {

        /** 映射表未命中时的策略：pass（默认，放行）/ deny（403）。 */
        private String unmatchedStrategy = "pass";

        /** 默认放行的基础设施路径（可整体覆盖）。 */
        private List<String> excludePaths = new ArrayList<String>(
                Arrays.asList("/actuator/**", "/error", "/favicon.ico"));

        public String getUnmatchedStrategy() {
            return unmatchedStrategy;
        }

        public void setUnmatchedStrategy(String unmatchedStrategy) {
            this.unmatchedStrategy = unmatchedStrategy;
        }

        public List<String> getExcludePaths() {
            return excludePaths;
        }

        public void setExcludePaths(List<String> excludePaths) {
            this.excludePaths = excludePaths;
        }

        /** @return true 表示映射未命中/无缓存可用时返回 403。 */
        public boolean isDenyUnmatched() {
            return "deny".equalsIgnoreCase(unmatchedStrategy);
        }
    }

    /** 缓存策略。 */
    public static class Cache {

        /** 兜底短轮询间隔，默认 300s（5 分钟）。 */
        private Duration fallbackPollInterval = Duration.ofSeconds(300);

        public Duration getFallbackPollInterval() {
            return fallbackPollInterval;
        }

        public void setFallbackPollInterval(Duration fallbackPollInterval) {
            this.fallbackPollInterval = fallbackPollInterval;
        }

        /** @return 轮询间隔毫秒数；非法/非正值回退到 300s。 */
        public long getFallbackPollIntervalMillis() {
            if (fallbackPollInterval == null || fallbackPollInterval.isNegative()
                    || fallbackPollInterval.isZero()) {
                return Duration.ofSeconds(300).toMillis();
            }
            return fallbackPollInterval.toMillis();
        }
    }

    /** 候选权限码生成规则。 */
    public static class CodeGenerator {

        private String pattern = "{systemCode}:{resource}:{action}";

        /** 资源名来源，当前支持 controller-class（默认）。 */
        private String resourceFrom = "controller-class";

        /** 动作名来源，当前支持 http-method（默认）。 */
        private String actionFrom = "http-method";

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getResourceFrom() {
            return resourceFrom;
        }

        public void setResourceFrom(String resourceFrom) {
            this.resourceFrom = resourceFrom;
        }

        public String getActionFrom() {
            return actionFrom;
        }

        public void setActionFrom(String actionFrom) {
            this.actionFrom = actionFrom;
        }
    }
}
