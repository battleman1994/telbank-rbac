package com.telbank.rbac.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 开放接口客户端的 wire 行为（Boot 4 / Spring 7 RestClient 版）：
 * URL/query 构造、签名头、响应信封解析、非 2xx 失败。
 *
 * <p>使用 {@link MockRestServiceServer} 捕获真实发出的请求（方法/URI/头/体），
 * 再用签名器公式独立重算签名做逐字节校验，不访问网络。</p>
 */
class HttpRbacOpenApiClientTest {

    private static final String SECRET = "crm-dev-secret";

    @Test
    void fetchUserPermissionsSendsSignedRequestAndParsesEnvelope() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.expectSuccess("{\"code\":0,\"message\":\"ok\",\"data\":{"
                + "\"permissions\":[{\"code\":\"order:delete\",\"validFrom\":null,"
                + "\"validUntil\":\"2026-10-31T23:59:59Z\"}],"
                + "\"serverTime\":\"2026-10-24T05:00:00Z\"}}");

        UserPermissionResponse response = fixture.api.fetchUserPermissions("13800138000");

        assertThat(response).isNotNull();
        assertThat(response.getServerTime()).isEqualTo("2026-10-24T05:00:00Z");
        assertThat(response.getPermissions()).hasSize(1);
        assertThat(response.getPermissions().get(0).getCode()).isEqualTo("order:delete");
        assertThat(response.getPermissions().get(0).getValidUntil())
                .isEqualTo("2026-10-31T23:59:59Z");

        CapturedRequest request = fixture.captured;
        assertThat(request.method).isEqualTo(HttpMethod.GET);
        assertThat(request.uri.getPath()).isEqualTo("/openapi/sdk/user-permissions");
        // query 按 key 排序、value URL 编码；签名侧只对实际发送的原始串重排
        assertThat(request.uri.getRawQuery())
                .isEqualTo("externalUserId=13800138000&systemCode=crm");
        assertThat(RbacSigner.sortedQuery(request.uri.getRawQuery()))
                .isEqualTo(request.uri.getRawQuery());
        assertSignatureMatches(request, "/openapi/sdk/user-permissions", request.uri.getRawQuery(), null);
        fixture.server.verify();
    }

    @Test
    void signingPathIncludesBaseUrlPathPrefix() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com/rbac-center"));
        fixture.expectSuccess("{\"code\":0,\"message\":\"ok\",\"data\":{\"systemCode\":\"crm\",\"mappings\":[]}}");

        List<MappingEntry> mappings = fixture.api.fetchMappings();

        assertThat(mappings).isEmpty();
        CapturedRequest request = fixture.captured;
        assertThat(request.uri.getPath()).isEqualTo("/rbac-center/openapi/sdk/mappings");
        assertSignatureMatches(request, "/rbac-center/openapi/sdk/mappings", request.uri.getRawQuery(),
                null);
        fixture.server.verify();
    }

    @Test
    void non2xxResponseThrowsRbacRemoteException() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.server.expect(fixture.captured)
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":401,\"message\":\"invalid signature\",\"data\":null}"));

        assertThatThrownBy(() -> fixture.api.fetchMappings())
                .isInstanceOf(RbacRemoteException.class)
                .satisfies(error -> {
                    RbacRemoteException remoteException = (RbacRemoteException) error;
                    assertThat(remoteException.getStatusCode()).isEqualTo(401);
                    assertThat(remoteException.getMessage()).contains("invalid signature");
                });
    }

    @Test
    void syncEndpointsPostsJsonBodyWithSignatureOverBody() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.expectSuccess(
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"received\":1,\"created\":1,\"updated\":0}}");
        List<EndpointInfo> endpoints = Collections.singletonList(new EndpointInfo("GET",
                "/api/order/list", "crm:order:list", EndpointInfo.SOURCE_GENERATED));

        fixture.api.syncEndpoints(endpoints);

        CapturedRequest request = fixture.captured;
        assertThat(request.method).isEqualTo(HttpMethod.POST);
        assertThat(request.uri.toString()).isEqualTo(
                "https://rbac.example.com/openapi/sdk/endpoints/sync");
        String body = new String(request.body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"systemCode\":\"crm\"")
                .contains("\"method\":\"GET\"")
                .contains("\"path\":\"/api/order/list\"")
                .contains("\"candidateCode\":\"crm:order:list\"")
                .contains("\"source\":\"GENERATED\"");
        assertThat(request.header(HttpHeaders.CONTENT_TYPE)).contains("application/json");
        assertSignatureMatches(request, "/openapi/sdk/endpoints/sync", "", request.body);
        fixture.server.verify();
    }

    @Test
    void fetchUserSystemsParsesRoleDerivedSystemList() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.expectSuccess("{\"code\":0,\"message\":\"ok\",\"data\":["
                + "{\"code\":\"crm\",\"name\":\"CRM系统\",\"region\":\"region_a\",\"isDefault\":true},"
                + "{\"code\":\"erp\",\"name\":\"ERP系统\",\"region\":\"region_b\",\"isDefault\":false}]}");

        List<SystemInfo> systems = fixture.api.fetchUserSystems("13600136000");

        assertThat(systems).hasSize(2);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(systems.get(0).getName()).isEqualTo("CRM系统");
        assertThat(systems.get(0).getIsDefault()).isTrue();
        assertThat(systems.get(1).getCode()).isEqualTo("erp");
        assertThat(systems.get(1).getIsDefault()).isFalse();
        assertThat(fixture.captured.uri.getRawQuery()).isEqualTo("externalUserId=13600136000");
        fixture.server.verify();
    }

    @Test
    void fetchUserSystemsParsesEmptyArrayAsNoValidRole() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.expectSuccess("{\"code\":0,\"message\":\"ok\",\"data\":[]}");

        assertThat(fixture.api.fetchUserSystems("13700137000")).isEmpty();
        fixture.server.verify();
    }

    @Test
    void malformedJsonFailsWithRemoteException() {
        HttpFixture fixture = new HttpFixture(properties("https://rbac.example.com"));
        fixture.server.expect(fixture.captured)
                .andRespond(withSuccess("<html>bad gateway</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> fixture.api.fetchMappings()).isInstanceOf(RbacRemoteException.class);
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

    private static void assertSignatureMatches(CapturedRequest request, String signingPath,
            String rawQuery, byte[] body) {
        String timestamp = request.header(RbacSigner.HEADER_TIMESTAMP);
        String nonce = request.header(RbacSigner.HEADER_NONCE);
        assertThat(request.header(RbacSigner.HEADER_APP_KEY)).isEqualTo("crm");
        assertThat(timestamp).isNotBlank();
        assertThat(nonce).isNotBlank();
        byte[] effectiveBody = body == null || body.length == 0 ? null : body;
        String expectedSignature = RbacSigner.computeSignature(SECRET, "crm", timestamp, nonce,
                request.method.name(), signingPath, rawQuery, effectiveBody);
        assertThat(request.header(RbacSigner.HEADER_SIGNATURE)).isEqualTo(expectedSignature)
                .matches("[0-9a-f]{64}");
    }

    /**
     * MSRV 夹具：捕获 SDK 实际发出的请求，并预置成功响应。
     */
    private static final class HttpFixture {

        private final RestClient.Builder builder = RestClient.builder();

        private final MockRestServiceServer server;

        private final CapturedRequest captured = new CapturedRequest();

        private final HttpRbacOpenApiClient api;

        private HttpFixture(RbacSdkProperties properties) {
            this.server = MockRestServiceServer.bindTo(builder).build();
            this.api = new HttpRbacOpenApiClient(properties, new RbacSigner("crm", SECRET),
                    builder.build(), RbacResponseDecoder.createObjectMapper());
        }

        private void expectSuccess(String body) {
            server.expect(captured).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
    }

    /**
     * 捕获请求的 RequestMatcher（MockRestServiceServer 将请求交给它匹配）。
     */
    private static final class CapturedRequest implements RequestMatcher {

        private volatile HttpMethod method;

        private volatile URI uri;

        private volatile HttpHeaders headers;

        private volatile byte[] body;

        @Override
        public void match(ClientHttpRequest request) {
            this.method = request.getMethod();
            this.uri = request.getURI();
            this.headers = request.getHeaders();
            if (request instanceof MockClientHttpRequest) {
                this.body = ((MockClientHttpRequest) request).getBodyAsBytes();
            }
        }

        private String header(String name) {
            List<String> values = headers == null ? null : headers.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            return values.get(0);
        }

        @Override
        public String toString() {
            return "CapturedRequest{" + method + ' ' + uri + '}';
        }
    }
}
