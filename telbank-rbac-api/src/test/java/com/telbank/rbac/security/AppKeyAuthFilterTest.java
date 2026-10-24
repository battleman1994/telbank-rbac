package com.telbank.rbac.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;

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

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private AppKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        properties.setClients(List.of(client("crm", "crm", CRM_SECRET), client("erp", "erp", "erp-dev-secret")));
        properties.getApproval().setAppKey("approval-system");
        properties.getApproval().setAppSecret(APPROVAL_SECRET);
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
