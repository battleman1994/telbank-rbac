package com.telbank.rbac.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 开放接口客户端（声明式 Feign）的 wire 行为：URL/query 构造、签名头、JSON 信封解析、非 2xx 失败。
 * 使用内存 fake {@link feign.Client}，不访问网络。
 */
class HttpRbacOpenApiClientTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String SECRET = "crm-dev-secret";

    @Test
    void fetchUserPermissionsSendsSignedRequestAndParsesEnvelope() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{"
                        + "\"permissions\":[{\"code\":\"order:delete\",\"validFrom\":null,"
                        + "\"validUntil\":\"2026-10-31T23:59:59Z\"}],"
                        + "\"serverTime\":\"2026-10-24T05:00:00Z\"}}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);

        UserPermissionResponse response = api.fetchUserPermissions("13800138000");

        assertThat(response).isNotNull();
        assertThat(response.getServerTime()).isEqualTo("2026-10-24T05:00:00Z");
        assertThat(response.getPermissions()).hasSize(1);
        assertThat(response.getPermissions().get(0).getCode()).isEqualTo("order:delete");
        assertThat(response.getPermissions().get(0).getValidUntil())
                .isEqualTo("2026-10-31T23:59:59Z");

        feign.Request request = client.lastRequest;
        assertThat(request.httpMethod().name()).isEqualTo("GET");
        URI uri = URI.create(request.url());
        assertThat(uri.getPath()).isEqualTo("/openapi/sdk/user-permissions");
        String rawQuery = uri.getRawQuery();
        // query 按 key 排序、value URL 编码；签名侧只对实际发送的原始串重排
        assertThat(rawQuery).isEqualTo("externalUserId=13800138000&systemCode=crm");
        assertThat(RbacSigner.sortedQuery(rawQuery)).isEqualTo(rawQuery);

        String timestamp = header(request, RbacSigner.HEADER_TIMESTAMP);
        String nonce = header(request, RbacSigner.HEADER_NONCE);
        assertThat(header(request, RbacSigner.HEADER_APP_KEY)).isEqualTo("crm");
        String expectedSignature = RbacSigner.computeSignature(SECRET, "crm", timestamp, nonce,
                "GET", "/openapi/sdk/user-permissions", rawQuery, null);
        assertThat(header(request, RbacSigner.HEADER_SIGNATURE)).isEqualTo(expectedSignature);
    }

    @Test
    void signingPathIncludesBaseUrlPathPrefix() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"systemCode\":\"crm\",\"mappings\":[]}}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com/rbac-center"), client);

        List<MappingEntry> mappings = api.fetchMappings();

        assertThat(mappings).isEmpty();
        URI uri = URI.create(client.lastRequest.url());
        assertThat(uri.getPath()).isEqualTo("/rbac-center/openapi/sdk/mappings");
        String expectedSignature = RbacSigner.computeSignature(SECRET, "crm",
                header(client.lastRequest, RbacSigner.HEADER_TIMESTAMP),
                header(client.lastRequest, RbacSigner.HEADER_NONCE), "GET",
                "/rbac-center/openapi/sdk/mappings", uri.getRawQuery(), null);
        assertThat(header(client.lastRequest, RbacSigner.HEADER_SIGNATURE))
                .isEqualTo(expectedSignature);
    }

    @Test
    void non2xxResponseThrowsRbacRemoteException() {
        CapturingClient client = new CapturingClient(401,
                "{\"code\":401,\"message\":\"invalid signature\",\"data\":null}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);

        assertThatThrownBy(() -> api.fetchMappings())
                .isInstanceOf(RbacRemoteException.class)
                .satisfies(error -> {
                    RbacRemoteException remoteException = (RbacRemoteException) error;
                    assertThat(remoteException.getStatusCode()).isEqualTo(401);
                    assertThat(remoteException.getMessage()).contains("invalid signature");
                });
    }

    @Test
    void syncEndpointsPostsJsonBodyWithSignatureOverBody() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"received\":1,\"created\":1,\"updated\":0}}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);
        List<EndpointInfo> endpoints = Collections.singletonList(new EndpointInfo("GET",
                "/api/order/list", "crm:order:list", EndpointInfo.SOURCE_GENERATED));

        api.syncEndpoints(endpoints);

        feign.Request request = client.lastRequest;
        assertThat(request.httpMethod().name()).isEqualTo("POST");
        assertThat(request.url()).isEqualTo("https://rbac.example.com/openapi/sdk/endpoints/sync");
        String body = new String(request.body(), UTF_8);
        assertThat(body).contains("\"systemCode\":\"crm\"")
                .contains("\"method\":\"GET\"")
                .contains("\"path\":\"/api/order/list\"")
                .contains("\"candidateCode\":\"crm:order:list\"")
                .contains("\"source\":\"GENERATED\"");
        assertThat(header(request, "Content-Type")).contains("application/json");
        String expectedSignature = RbacSigner.computeSignature(SECRET, "crm",
                header(request, RbacSigner.HEADER_TIMESTAMP),
                header(request, RbacSigner.HEADER_NONCE), "POST",
                "/openapi/sdk/endpoints/sync", "", request.body());
        assertThat(header(request, RbacSigner.HEADER_SIGNATURE)).isEqualTo(expectedSignature);
    }

    @Test
    void fetchUserSystemsParsesRoleDerivedSystemList() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":["
                        + "{\"code\":\"crm\",\"name\":\"CRM系统\",\"region\":\"region_a\",\"isDefault\":true},"
                        + "{\"code\":\"erp\",\"name\":\"ERP系统\",\"region\":\"region_b\",\"isDefault\":false}]}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);

        List<SystemInfo> systems = api.fetchUserSystems("13600136000");

        assertThat(systems).hasSize(2);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(systems.get(0).getName()).isEqualTo("CRM系统");
        assertThat(systems.get(0).getIsDefault()).isTrue();
        assertThat(systems.get(1).getCode()).isEqualTo("erp");
        assertThat(systems.get(1).getIsDefault()).isFalse();
        assertThat(URI.create(client.lastRequest.url()).getRawQuery())
                .isEqualTo("externalUserId=13600136000");
    }

    @Test
    void fetchUserSystemsParsesEmptyArrayAsNoValidRole() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":[]}");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);

        assertThat(api.fetchUserSystems("13700137000")).isEmpty();
    }

    @Test
    void reportOnboardingUsesOnboardingKeyAndSignature() {
        CapturingClient client = new CapturingClient(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"applicationId\":123,"
                        + "\"status\":\"PENDING\"}}");
        RbacSdkProperties properties = properties("https://rbac.example.com");
        properties.getOnboarding().setEnabled(true);
        properties.getOnboarding().setAppKey("onboarding-app");
        properties.getOnboarding().setAppSecret("onboarding-secret");
        HttpRbacOpenApiClient api = api(properties, client);

        OnboardingApplyResult result = api.reportOnboarding(new OnboardingApplyRequest("demo-sdk",
                "Demo SDK 系统", "region_a", "1.0.0", null));

        assertThat(result.getApplicationId()).isEqualTo(123L);
        assertThat(result.getStatus()).isEqualTo("PENDING");

        feign.Request request = client.lastRequest;
        assertThat(request.httpMethod().name()).isEqualTo("POST");
        assertThat(URI.create(request.url()).getPath()).isEqualTo("/openapi/onboarding/systems");
        String body = new String(request.body(), UTF_8);
        assertThat(body).contains("\"systemCode\":\"demo-sdk\"")
                .contains("\"name\":\"Demo SDK 系统\"")
                .contains("\"region\":\"region_a\"")
                .contains("\"sdkVersion\":\"1.0.0\"");
        // onboarding 专用 AppKey 签名（不是业务系统主 AppKey）
        assertThat(header(request, RbacSigner.HEADER_APP_KEY)).isEqualTo("onboarding-app");
        String expected = RbacSigner.computeSignature("onboarding-secret", "onboarding-app",
                header(request, RbacSigner.HEADER_TIMESTAMP), header(request, RbacSigner.HEADER_NONCE),
                "POST", "/openapi/onboarding/systems", "", request.body());
        assertThat(header(request, RbacSigner.HEADER_SIGNATURE)).isEqualTo(expected);
    }

    @Test
    void malformedJsonFailsWithRemoteException() {
        CapturingClient client = new CapturingClient(200, "<html>bad gateway</html>");
        HttpRbacOpenApiClient api = api(properties("https://rbac.example.com"), client);

        assertThatThrownBy(() -> api.fetchMappings()).isInstanceOf(RbacRemoteException.class);
    }

    private static HttpRbacOpenApiClient api(RbacSdkProperties properties, feign.Client client) {
        return new HttpRbacOpenApiClient(properties, new RbacSigner("crm", SECRET), client,
                JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
    }

    private static RbacSdkProperties properties(String baseUrl) {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl(baseUrl);
        properties.setAppKey("crm");
        properties.setAppSecret(SECRET);
        properties.setAppVersion("1.0.0");
        properties.validate();
        return properties;
    }

    private static String header(feign.Request request, String name) {
        for (Map.Entry<String, Collection<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().iterator().next();
            }
        }
        return null;
    }

    /**
     * 捕获请求并返回预置响应的 feign.Client。
     */
    private static final class CapturingClient implements feign.Client {

        private final int status;

        private final String responseBody;

        private volatile feign.Request lastRequest;

        private CapturingClient(int status, String responseBody) {
            this.status = status;
            this.responseBody = responseBody;
        }

        @Override
        public feign.Response execute(feign.Request request, feign.Request.Options options) {
            this.lastRequest = request;
            return feign.Response.builder()
                    .status(status)
                    .reason("TEST")
                    .headers(Collections.<String, Collection<String>>emptyMap())
                    .request(request)
                    .body(responseBody, UTF_8)
                    .build();
        }
    }
}
