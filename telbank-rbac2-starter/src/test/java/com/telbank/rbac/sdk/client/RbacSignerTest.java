package com.telbank.rbac.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 签名规范化串 / HMAC 已知向量 / query 排序口径（契约 §4.1，SDK 与 api 必须逐字节一致）。
 */
class RbacSignerTest {

    @Test
    void canonicalStringMatchesContractDefinition() {
        String canonical = RbacSigner.canonicalString("crm", "1792819200000",
                "6f1c2c9a34b84b7f9a51", "GET", "/openapi/sdk/mappings", "systemCode=crm", new byte[0]);
        String expected = "crm\n"
                + "1792819200000\n"
                + "6f1c2c9a34b84b7f9a51\n"
                + "GET\n"
                + "/openapi/sdk/mappings\n"
                + "systemCode=crm\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertThat(canonical).isEqualTo(expected);
        // 已知向量：SHA-256("") 的小写 hex
        assertThat(RbacSigner.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        // body 为 null 与空串等价（GET 无 body）
        assertThat(RbacSigner.canonicalString("crm", "1792819200000", "6f1c2c9a34b84b7f9a51",
                "GET", "/openapi/sdk/mappings", "systemCode=crm", null)).isEqualTo(canonical);
    }

    @Test
    void hmacSha256MatchesKnownVector() {
        // RFC 4231 Test Case 2
        assertThat(RbacSigner.hmacSha256Hex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void sortedQuerySortsRawPairsByRawKeyWithoutReencoding() {
        assertThat(RbacSigner.sortedQuery("systemCode=crm&externalUserId=13800138000"))
                .isEqualTo("externalUserId=13800138000&systemCode=crm");
        // 不解码/不重编码：保留发送时的原始 %XX 字节
        assertThat(RbacSigner.sortedQuery("systemCode=crm&externalUserId=a%2Bb%40c.com"))
                .isEqualTo("externalUserId=a%2Bb%40c.com&systemCode=crm");
        // 同一 key 保持出现顺序（稳定排序）
        assertThat(RbacSigner.sortedQuery("b=2&a=1&a=0")).isEqualTo("a=1&a=0&b=2");
        assertThat(RbacSigner.sortedQuery("")).isEmpty();
        assertThat(RbacSigner.sortedQuery(null)).isEmpty();
    }

    @Test
    void buildQuerySortsKeysAndUrlEncodesValues() {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("systemCode", "crm");
        query.put("externalUserId", "a+b@c.com");
        query.put("pageCode", "order:list");
        String rawQuery = RbacSigner.buildQuery(query);
        assertThat(rawQuery)
                .isEqualTo("externalUserId=a%2Bb%40c.com&pageCode=order%3Alist&systemCode=crm");
        // 构造出的 rawQuery 已是排序后的原始串，签名侧重排应保持不变（逐字节一致）
        assertThat(RbacSigner.sortedQuery(rawQuery)).isEqualTo(rawQuery);
        assertThat(RbacSigner.buildQuery(null)).isEmpty();
    }

    @Test
    void signHeadersUseCanonicalAndHmac() {
        RbacSigner signer = new RbacSigner("crm", "crm-dev-secret");
        String rawQuery = "externalUserId=13800138000&systemCode=crm";
        Map<String, String> headers = signer.signHeaders("GET", "/openapi/sdk/user-permissions",
                rawQuery, null, "1792819200000", "6f1c2c9a34b84b7f9a51");

        assertThat(headers).containsEntry(RbacSigner.HEADER_APP_KEY, "crm")
                .containsEntry(RbacSigner.HEADER_TIMESTAMP, "1792819200000")
                .containsEntry(RbacSigner.HEADER_NONCE, "6f1c2c9a34b84b7f9a51");
        String expectedSignature = RbacSigner.hmacSha256Hex("crm-dev-secret",
                RbacSigner.canonicalString("crm", "1792819200000", "6f1c2c9a34b84b7f9a51", "GET",
                        "/openapi/sdk/user-permissions", rawQuery, null));
        assertThat(headers.get(RbacSigner.HEADER_SIGNATURE)).isEqualTo(expectedSignature)
                .matches("[0-9a-f]{64}");
        // 直接调用契约公式与 computeSignature 等价
        assertThat(RbacSigner.computeSignature("crm-dev-secret", "crm", "1792819200000",
                "6f1c2c9a34b84b7f9a51", "GET", "/openapi/sdk/user-permissions", rawQuery, null))
                .isEqualTo(expectedSignature);
    }

    @Test
    void generatedHeadersContainMillisTimestampAndLongNonce() {
        RbacSigner signer = new RbacSigner("crm", "crm-dev-secret");
        long before = System.currentTimeMillis();
        Map<String, String> headers = signer.signHeaders("POST", "/openapi/sdk/endpoints/sync", "",
                "{}".getBytes(java.nio.charset.Charset.forName("UTF-8")));
        long after = System.currentTimeMillis();

        assertThat(headers.get(RbacSigner.HEADER_NONCE)).hasSizeGreaterThanOrEqualTo(16)
                .matches("[0-9a-f]+");
        long timestamp = Long.parseLong(headers.get(RbacSigner.HEADER_TIMESTAMP));
        assertThat(timestamp).isBetween(before - 1, after + 1);
        assertThat(headers.get(RbacSigner.HEADER_SIGNATURE)).matches("[0-9a-f]{64}");
    }

    @Test
    void httpMethodIsNormalizedToUpperCaseInCanonicalString() {
        String lower = RbacSigner.canonicalString("crm", "1", "n", "get", "/x", "", null);
        String upper = RbacSigner.canonicalString("crm", "1", "n", "GET", "/x", "", null);
        assertThat(lower).isEqualTo(upper);
    }
}
