package com.telbank.rbac.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名规范串与 HMAC-SHA256（契约 §4.1）：使用固定向量验证，保证 SDK/验收脚本逐字节一致。
 */
class OpenApiSignatureTest {

    private static final byte[] EMPTY_BODY = new byte[0];

    @Test
    void sha256OfEmptyBodyMatchesKnownVector() {
        assertThat(HmacSignatures.sha256Hex(EMPTY_BODY))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hmacSha256MatchesKnownVector() {
        // key="key", data="The quick brown fox jumps over the lazy dog"
        assertThat(HmacSignatures.hmacSha256Hex("key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void canonicalStringUsesRawSortedQueryWithoutReEncoding() {
        String canonical = OpenApiSignature.canonicalString(
                "crm", "1792819200000", "nonce-1234567890",
                "get", "/openapi/sdk/user-permissions",
                "externalUserId=13800138000&systemCode=crm", EMPTY_BODY);
        String expected = String.join("\n",
                "crm",
                "1792819200000",
                "nonce-1234567890",
                "GET",
                "/openapi/sdk/user-permissions",
                "externalUserId=13800138000&systemCode=crm",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(canonical).isEqualTo(expected);
    }

    @Test
    void sortedQueryKeepsOriginalBytesAndOrderForEqualKeys() {
        // 不做解码/重编码：空格、中文、%2F 均保持发送时原样
        assertThat(OpenApiSignature.sortedQuery("b=2&a=%E4%B8%AD%E6%96%87&a=1&c=x%20y"))
                .isEqualTo("a=%E4%B8%AD%E6%96%87&a=1&b=2&c=x%20y");
        assertThat(OpenApiSignature.sortedQuery(null)).isEmpty();
        assertThat(OpenApiSignature.sortedQuery("")).isEmpty();
        assertThat(OpenApiSignature.sortedQuery("a=1")).isEqualTo("a=1");
    }

    @Test
    void expectedSignatureIsLowercaseHexAndVerifies() {
        String canonical = OpenApiSignature.canonicalString("crm", "1792819200000", "nonce-1234567890",
                "GET", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY);
        String signature = HmacSignatures.hmacSha256Hex(
                "crm-dev-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), canonical);
        assertThat(signature).matches("[0-9a-f]{64}");
        assertThat(OpenApiSignature.verify("crm-dev-secret", "crm", "1792819200000", "nonce-1234567890",
                "GET", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY, signature)).isTrue();
        // 大小写不敏感
        assertThat(OpenApiSignature.verify("crm-dev-secret", "crm", "1792819200000", "nonce-1234567890",
                "GET", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY, signature.toUpperCase())).isTrue();
    }

    @Test
    void tamperedRequestFailsVerification() {
        String signature = OpenApiSignature.expectedSignature("crm-dev-secret", "crm", "1792819200000",
                "nonce-1234567890", "GET", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY);
        assertThat(OpenApiSignature.verify("crm-dev-secret", "crm", "1792819200000", "nonce-1234567890",
                "GET", "/openapi/sdk/mappings", "systemCode=erp", EMPTY_BODY, signature)).isFalse();
        assertThat(OpenApiSignature.verify("wrong-secret", "crm", "1792819200000", "nonce-1234567890",
                "GET", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY, signature)).isFalse();
        assertThat(OpenApiSignature.verify("crm-dev-secret", "crm", "1792819200000", "nonce-1234567890",
                "POST", "/openapi/sdk/mappings", "systemCode=crm", EMPTY_BODY, signature)).isFalse();
    }

    @Test
    void bodyIsIncludedInCanonicalString() {
        byte[] body = "{\"systemCode\":\"crm\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature = OpenApiSignature.expectedSignature("approval-dev-secret", "approval-system",
                "1792819200000", "nonce-approval-1", "POST", "/openapi/approval/grant", null, body);
        assertThat(OpenApiSignature.verify("approval-dev-secret", "approval-system", "1792819200000",
                "nonce-approval-1", "POST", "/openapi/approval/grant", null, body, signature)).isTrue();
        assertThat(OpenApiSignature.verify("approval-dev-secret", "approval-system", "1792819200000",
                "nonce-approval-1", "POST", "/openapi/approval/grant", null, EMPTY_BODY, signature)).isFalse();
    }

    @Test
    void constantTimeEqualsComparesContent() {
        assertThat(HmacSignatures.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(HmacSignatures.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(HmacSignatures.constantTimeEquals("abc", null)).isFalse();
        assertThat(HmacSignatures.constantTimeEquals(null, "abc")).isFalse();
    }
}
