package com.telbank.rbac.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 开放接口 AppKey + HMAC-SHA256 签名规范（契约 §4.1）。
 *
 * <p>规范化串（字段间 {@code \n}，无末尾换行）：</p>
 * <pre>
 * appKey + "\n" + timestamp + "\n" + nonce + "\n" + HTTP_METHOD + "\n" + path + "\n"
 *        + sortedQuery + "\n" + sha256Hex(body)
 * </pre>
 *
 * <p>sortedQuery 口径（契约 §7.7）：把原始 query 串按 {@code &} 切分，按 {@code =} 前原始 key
 * 升序重排后直接 {@code &} 拼接，<b>不解码、不重编码</b>；无 query 时为空串。</p>
 */
public final class OpenApiSignature {

    private OpenApiSignature() {
    }

    /**
     * 按契约口径重排原始 query 串（不 URL 解码/编码）。
     */
    public static String sortedQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : rawQuery.split("&", -1)) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        // 稳定排序：相同 key 保持原始顺序（与脚本 Python 的稳定排序一致）
        segments.sort(Comparator.comparing(OpenApiSignature::rawKey));
        return String.join("&", segments);
    }

    /**
     * 构造规范化串。
     */
    public static String canonicalString(String appKey, String timestamp, String nonce,
                                         String method, String path, String rawQuery, byte[] body) {
        return String.join("\n",
                nullToEmpty(appKey),
                nullToEmpty(timestamp),
                nullToEmpty(nonce),
                nullToEmpty(method).toUpperCase(Locale.ROOT),
                nullToEmpty(path),
                sortedQuery(rawQuery),
                HmacSignatures.sha256Hex(body));
    }

    /**
     * 计算期望签名（小写 hex）。
     */
    public static String expectedSignature(String appSecret, String appKey, String timestamp, String nonce,
                                           String method, String path, String rawQuery, byte[] body) {
        String canonical = canonicalString(appKey, timestamp, nonce, method, path, rawQuery, body);
        return HmacSignatures.hmacSha256Hex(appSecret.getBytes(StandardCharsets.UTF_8), canonical);
    }

    /**
     * 校验签名（常量时间比较；入参大小写不敏感）。
     */
    public static boolean verify(String appSecret, String appKey, String timestamp, String nonce,
                                 String method, String path, String rawQuery, byte[] body,
                                 String providedSignature) {
        if (appSecret == null || providedSignature == null) {
            return false;
        }
        String expected = expectedSignature(appSecret, appKey, timestamp, nonce, method, path, rawQuery, body);
        return HmacSignatures.constantTimeEquals(expected, providedSignature.trim().toLowerCase(Locale.ROOT));
    }

    private static String rawKey(String segment) {
        int index = segment.indexOf('=');
        return index < 0 ? segment : segment.substring(0, index);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
