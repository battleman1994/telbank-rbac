package com.telbank.rbac.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.testutil.TestProxies;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /openapi/**} AppKey + HMAC-SHA256 验签过滤器（契约 §4.1）：
 * 合法签名放行；签名错误 / 时间戳过期 / nonce 重放 / appKey 与 systemCode 不一致均 401。
 */
class AppKeyAuthFilterTest {

    private static final String CRM_SECRET = "crm-dev-secret";
    private static final String APPROVAL_SECRET = "approval-dev-secret";
    private static final String ONBOARDING_APP_KEY = "onboarding-app";
    private static final String ONBOARDING_SECRET = "onboarding-secret";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RbacProperties properties;
    private AppKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RbacProperties();
        properties.setClients(List.of(client("crm", "crm", CRM_SECRET), client("erp", "erp", "erp-dev-secret")));
        properties.getApproval().setAppKey("approval-system");
        properties.getApproval().setAppSecret(APPROVAL_SECRET);
        properties.getOnboarding().setAppKey(ONBOARDING_APP_KEY);
        properties.getOnboarding().setAppSecret(ONBOARDING_SECRET);
        filter = new AppKeyAuthFilter(jsonMapper, properties, new CaffeineNonceStore());
    }

    @Test
    void passesThroughWithValidSignature() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        request.setQueryString("systemCode=crm");
        sign(request, "crm", CRM_SECRET, "nonce-valid-000001", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        OpenApiCaller caller = (OpenApiCaller) request.getAttribute(RbacConstants.ATTR_OPENAPI_CALLER);
        assertThat(caller).isNotNull();
        assertThat(caller.appKey()).isEqualTo("crm");
        assertThat(caller.systemCode()).isEqualTo("crm");
        assertThat(caller.approval()).isFalse();
    }

    @Test
    void rejectsBadSignatureWithEnvelopeAnd401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        request.setQueryString("systemCode=crm");
        request.addHeader(RbacConstants.HEADER_APP_KEY, "crm");
        request.addHeader(RbacConstants.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        request.addHeader(RbacConstants.HEADER_NONCE, "nonce-bad-signature");
        request.addHeader(RbacConstants.HEADER_SIGNATURE, "deadbeef");

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":401")
                .contains("invalid signature");
    }

    @Test
    void rejectsExpiredTimestamp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        request.setQueryString("systemCode=crm");
        long tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
        sign(request, "crm", CRM_SECRET, "nonce-expired-0001", tenMinutesAgo);

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        String nonce = "nonce-replay-000001";
        long timestamp = System.currentTimeMillis();

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        first.setQueryString("systemCode=crm");
        sign(first, "crm", CRM_SECRET, nonce, timestamp);
        RecordingChain firstChain = new RecordingChain();
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.invoked).isTrue();

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        second.setQueryString("systemCode=crm");
        sign(second, "crm", CRM_SECRET, nonce, timestamp);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        RecordingChain secondChain = new RecordingChain();
        filter.doFilter(second, secondResponse, secondChain);

        assertThat(secondChain.invoked).isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsAppKeySystemCodeMismatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        // 用 CRM 的 AppKey 签名，但请求 ERP 的 systemCode
        request.setQueryString("systemCode=erp");
        sign(request, "crm", CRM_SECRET, "nonce-mismatch-01", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMissingHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/connection");
        request.setQueryString("systemCode=crm");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void verifiesApprovalCallbackWithDedicatedAppKey() throws Exception {
        String body = "{\"systemCode\":\"crm\",\"externalUserId\":\"13800138000\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/approval/grant");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        sign(request, "approval-system", APPROVAL_SECRET, "nonce-approval-001", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        OpenApiCaller caller = (OpenApiCaller) request.getAttribute(RbacConstants.ATTR_OPENAPI_CALLER);
        assertThat(caller.approval()).isTrue();
    }

    @Test
    void verifiesOnboardingReportWithDedicatedAppKeyWithoutSystemCodeBinding() throws Exception {
        String body = "{\"systemCode\":\"finance\",\"name\":\"财务系统\",\"region\":\"region_a\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/onboarding/systems");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        sign(request, ONBOARDING_APP_KEY, ONBOARDING_SECRET, "nonce-onboarding-01", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        OpenApiCaller caller = (OpenApiCaller) request.getAttribute(RbacConstants.ATTR_OPENAPI_CALLER);
        assertThat(caller.onboarding()).isTrue();
        assertThat(caller.approval()).isFalse();
        assertThat(caller.systemCode()).isNull();
        assertThat(caller.appKey()).isEqualTo(ONBOARDING_APP_KEY);
    }

    @Test
    void rejectsOnboardingReportWithBadSignature() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/onboarding/systems");
        request.setContent("{\"systemCode\":\"finance\"}".getBytes(StandardCharsets.UTF_8));
        sign(request, ONBOARDING_APP_KEY, "wrong-secret", "nonce-onboarding-02", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsUnknownOnboardingAppKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/onboarding/systems");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        sign(request, "crm", CRM_SECRET, "nonce-onboarding-03", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsOnboardingWhenDisabled() throws Exception {
        properties.getOnboarding().setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/onboarding/systems");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        sign(request, ONBOARDING_APP_KEY, ONBOARDING_SECRET, "nonce-onboarding-04", System.currentTimeMillis());

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void databaseAppKeyTakesPrecedenceOverYamlClients() throws Exception {
        SysSystem system = new SysSystem();
        system.setId(1L);
        system.setCode("crm");
        system.setStatus(1);
        system.setIsPlatform(0);
        system.setAppKey("crm");
        system.setAppSecret("db-crm-secret");
        SysSystemMapper systemMapper = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> system;
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        AppKeyAuthFilter dbFilter = new AppKeyAuthFilter(jsonMapper, properties, new CaffeineNonceStore(),
                new SystemAppKeyRegistry(systemMapper));

        // DB secret 生效：用 YAML demo secret 签名应 401
        MockHttpServletRequest yamlSigned = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        yamlSigned.setQueryString("systemCode=crm");
        sign(yamlSigned, "crm", CRM_SECRET, "nonce-db-priority-1", System.currentTimeMillis());
        MockHttpServletResponse yamlResponse = new MockHttpServletResponse();
        RecordingChain yamlChain = new RecordingChain();
        dbFilter.doFilter(yamlSigned, yamlResponse, yamlChain);
        assertThat(yamlChain.invoked).isFalse();
        assertThat(yamlResponse.getStatus()).isEqualTo(401);

        // DB secret 生效：签名放行
        MockHttpServletRequest dbSigned = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        dbSigned.setQueryString("systemCode=crm");
        sign(dbSigned, "crm", "db-crm-secret", "nonce-db-priority-2", System.currentTimeMillis());
        MockHttpServletResponse dbResponse = new MockHttpServletResponse();
        RecordingChain dbChain = new RecordingChain();
        dbFilter.doFilter(dbSigned, dbResponse, dbChain);
        assertThat(dbChain.invoked).isTrue();
        OpenApiCaller caller = (OpenApiCaller) dbSigned.getAttribute(RbacConstants.ATTR_OPENAPI_CALLER);
        assertThat(caller.systemCode()).isEqualTo("crm");
        assertThat(caller.onboarding()).isFalse();
    }

    @Test
    void disabledDatabaseSystemDoesNotFallBackToYaml() throws Exception {
        SysSystem system = new SysSystem();
        system.setId(1L);
        system.setCode("crm");
        system.setStatus(0);
        system.setIsPlatform(0);
        system.setAppKey("crm");
        system.setAppSecret(CRM_SECRET);
        SysSystemMapper systemMapper = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> system;
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        AppKeyAuthFilter dbFilter = new AppKeyAuthFilter(jsonMapper, properties, new CaffeineNonceStore(),
                new SystemAppKeyRegistry(systemMapper));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/sdk/mappings");
        request.setQueryString("systemCode=crm");
        sign(request, "crm", CRM_SECRET, "nonce-db-disabled-1", System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        dbFilter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void ignoresNonOpenApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static RbacProperties.Client client(String appKey, String systemCode, String appSecret) {
        RbacProperties.Client client = new RbacProperties.Client();
        client.setAppKey(appKey);
        client.setSystemCode(systemCode);
        client.setAppSecret(appSecret);
        return client;
    }

    private static void sign(MockHttpServletRequest request, String appKey, String appSecret,
                             String nonce, long timestamp) {
        byte[] body = request.getContentAsByteArray();
        String signature = OpenApiSignature.expectedSignature(appSecret, appKey, String.valueOf(timestamp),
                nonce, request.getMethod(), request.getRequestURI(), request.getQueryString(), body);
        request.addHeader(RbacConstants.HEADER_APP_KEY, appKey);
        request.addHeader(RbacConstants.HEADER_TIMESTAMP, String.valueOf(timestamp));
        request.addHeader(RbacConstants.HEADER_NONCE, nonce);
        request.addHeader(RbacConstants.HEADER_SIGNATURE, signature);
    }

    private static final class RecordingChain implements FilterChain {
        private boolean invoked;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.invoked = true;
        }
    }
}
