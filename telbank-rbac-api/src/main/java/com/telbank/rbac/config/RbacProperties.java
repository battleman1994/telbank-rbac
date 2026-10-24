package com.telbank.rbac.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限中心配置（契约 §8.1）。
 *
 * <pre>
 * rbac:
 *   security.jwt: {mode, jwk-set-uri, issuer, hs256-secret}
 *   clients: [{app-key, system-code, app-secret}]
 *   approval: {app-key, app-secret}
 *   regions: {region_a: {host, port, password, channel-prefix}}
 *   redis.enabled: true|false
 *   expiry-scan-cron: "0 * * * * *"
 * </pre>
 */
@ConfigurationProperties(prefix = "rbac")
public class RbacProperties {

    private final Security security = new Security();

    /** SDK 系统级 AppKey（每个系统一个）。 */
    private List<Client> clients = new ArrayList<>();

    /** 审批系统回调使用的独立 AppKey/AppSecret。 */
    private final Approval approval = new Approval();

    /** 区域 -> Redis 连接配置，用于发布变更通知与给 SDK 下发连接信息。 */
    private Map<String, Region> regions = new LinkedHashMap<>();

    private final Redis redis = new Redis();

    /** 过期授权扫描 cron，默认每分钟。 */
    private String expiryScanCron = "0 * * * * *";

    /** CORS 允许的来源（默认本地前端 dev server）。 */
    private final Cors cors = new Cors();

    public Security getSecurity() {
        return security;
    }

    public List<Client> getClients() {
        return clients;
    }

    public void setClients(List<Client> clients) {
        this.clients = clients == null ? new ArrayList<>() : clients;
    }

    public Approval getApproval() {
        return approval;
    }

    public Map<String, Region> getRegions() {
        return regions;
    }

    public void setRegions(Map<String, Region> regions) {
        this.regions = regions == null ? new LinkedHashMap<>() : regions;
    }

    public Redis getRedis() {
        return redis;
    }

    public String getExpiryScanCron() {
        return expiryScanCron;
    }

    public void setExpiryScanCron(String expiryScanCron) {
        this.expiryScanCron = expiryScanCron;
    }

    public Cors getCors() {
        return cors;
    }

    // ------------------------------------------------------------------

    public static class Security {

        private final Jwt jwt = new Jwt();

        public Jwt getJwt() {
            return jwt;
        }
    }

    public static class Jwt {

        /** jwk | hs256。 */
        private String mode = "jwk";
        private String jwkSetUri = "";
        private String issuer = "";
        private String hs256Secret = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getHs256Secret() {
            return hs256Secret;
        }

        public void setHs256Secret(String hs256Secret) {
            this.hs256Secret = hs256Secret;
        }
    }

    public static class Client {

        private String appKey;
        private String systemCode;
        private String appSecret;

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getSystemCode() {
            return systemCode;
        }

        public void setSystemCode(String systemCode) {
            this.systemCode = systemCode;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }
    }

    public static class Approval {

        private String appKey = "approval-system";
        private String appSecret = "";

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
    }

    public static class Region {

        private String host = "";
        private int port = 6379;
        private String password = "";
        private String channelPrefix = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getChannelPrefix() {
            return channelPrefix;
        }

        public void setChannelPrefix(String channelPrefix) {
            this.channelPrefix = channelPrefix;
        }
    }

    public static class Redis {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
        }
    }
}
