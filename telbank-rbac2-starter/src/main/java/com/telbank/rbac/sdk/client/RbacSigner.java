package com.telbank.rbac.sdk.client;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 权限中心开放接口签名实现（契约 §4.1，SDK 与 api 必须逐字节一致）。
 *
 * <p>请求头：</p>
 * <pre>
 * X-App-Key:   crm
 * X-Timestamp: 1792819200000          # epoch 毫秒
 * X-Nonce:     6f1c2c9a34b84b7f9a51   # 随机串，&gt;= 16 字符
 * X-Signature: &lt;hex HMAC-SHA256&gt;
 * </pre>
 *
 * <p>规范化串（字段间 {@code \n}，无末尾换行）：</p>
 * <pre>
 * appKey + "\n" + timestamp + "\n" + nonce + "\n" + HTTP_METHOD + "\n" + path + "\n"
 *        + sortedQuery + "\n" + sha256Hex(body)
 * </pre>
 *
 * <p>{@code sortedQuery}：把实际发送的原始 query 串按 {@code &} 切分（不做解码/重编码），
 * 按片段中 {@code =} 之前的原始 key 升序排序（同一 key 保持出现顺序），再用 {@code &} 拼回；
 * 无 query 时为 {@code ""}。</p>
 *
 * <p>{@code X-Signature = HMAC-SHA256(appSecret, canonical)} 的小写 hex。</p>
 */
public final class RbacSigner {

    public static final String HEADER_APP_KEY = "X-App-Key";

    public static final String HEADER_TIMESTAMP = "X-Timestamp";

    public static final String HEADER_NONCE = "X-Nonce";

    public static final String HEADER_SIGNATURE = "X-Signature";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final String SHA_256 = "SHA-256";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String appKey;

    private final String appSecret;

    public RbacSigner(String appKey, String appSecret) {
        if (isBlank(appKey)) {
            throw new IllegalArgumentException("rbac-sdk.app-key 不能为空");
        }
        if (isBlank(appSecret)) {
            throw new IllegalArgumentException("rbac-sdk.app-secret 不能为空");
        }
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public String getAppKey() {
        return appKey;
    }

    /**
     * 生成一次请求的签名头（时间戳取当前毫秒、nonce 随机）。
     *
     * @param httpMethod HTTP 方法
     * @param path       不含 query 的请求路径，如 /openapi/sdk/mappings
     * @param rawQuery   实际发送的原始 query 串（可空）
     * @param body       原始请求体字节（GET 为 null，按空串摘要）
     * @return 含四个签名头的有序 Map
     */
    public Map<String, String> signHeaders(String httpMethod, String path, String rawQuery, byte[] body) {
        return signHeaders(httpMethod, path, rawQuery, body, String.valueOf(System.currentTimeMillis()),
                generateNonce());
    }

    /**
     * 生成一次请求的签名头（时间戳/nonce 由调用方指定，便于单测与重放排查）。
     */
    public Map<String, String> signHeaders(String httpMethod, String path, String rawQuery, byte[] body,
            String timestamp, String nonce) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(HEADER_APP_KEY, appKey);
        headers.put(HEADER_TIMESTAMP, timestamp);
        headers.put(HEADER_NONCE, nonce);
        headers.put(HEADER_SIGNATURE,
                computeSignature(appSecret, appKey, timestamp, nonce, httpMethod, path, rawQuery, body));
        return headers;
    }

    /**
     * 计算签名（小写 hex）。
     */
    public static String computeSignature(String appSecret, String appKey, String timestamp, String nonce,
            String httpMethod, String path, String rawQuery, byte[] body) {
        String canonical = canonicalString(appKey, timestamp, nonce, httpMethod, path,
                sortedQuery(rawQuery), body);
        return hmacSha256Hex(appSecret, canonical);
    }

    /**
     * 拼接规范化串（body 为 null 时按空串摘要）。
     */
    public static String canonicalString(String appKey, String timestamp, String nonce, String httpMethod,
            String path, String sortedQuery, byte[] body) {
        return nullToEmpty(appKey) + '\n'
                + nullToEmpty(timestamp) + '\n'
                + nullToEmpty(nonce) + '\n'
                + normalizeMethod(httpMethod) + '\n'
                + nullToEmpty(path) + '\n'
                + nullToEmpty(sortedQuery) + '\n'
                + sha256Hex(body);
    }

    /**
     * 对实际发送的原始 query 串按原始 key 升序重排（不解码/不重编码，同一 key 保持出现顺序）。
     */
    public static String sortedQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String[] segments = rawQuery.split("&", -1);
        // Arrays.sort 对对象数组是稳定排序：同一 key 的片段保持出现顺序
        java.util.Arrays.sort(segments, new java.util.Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return rawKey(left).compareTo(rawKey(right));
            }
        });
        StringBuilder builder = new StringBuilder(rawQuery.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('&');
            }
            builder.append(segments[i]);
        }
        return builder.toString();
    }

    /**
     * 按 key 升序拼接 query（value 使用 {@link URLEncoder} 编码），用于构造实际发送的 query 串。
     *
     * <p>由于签名侧只对「实际发送的原始串」重排，本方法的输出可直接作为 rawQuery 传给
     * {@link #signHeaders(String, String, String, byte[])}。</p>
     */
    public static String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            String value = params.get(key);
            if (value == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(key).append('=').append(encodeQueryValue(value));
        }
        return builder.toString();
    }

    /**
     * URL query 值编码（UTF-8，application/x-www-form-urlencoded）。
     */
    public static String encodeQueryValue(String value) {
        try {
            return URLEncoder.encode(nullToEmpty(value), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 必然存在
            throw new IllegalStateException("UTF-8 编码不可用", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(nullToEmpty(text).getBytes(UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        return toHex(digest.digest(data == null ? new byte[0] : data));
    }

    public static String hmacSha256Hex(String appSecret, String data) {
        if (appSecret == null) {
            throw new IllegalArgumentException("appSecret 不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(appSecret.getBytes(UTF_8), HMAC_SHA256));
            return toHex(mac.doFinal(nullToEmpty(data).getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 生成请求 nonce：16 字节安全随机数的 32 位小写 hex（&gt;= 16 字符）。
     */
    public static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    private static String rawKey(String segment) {
        int index = segment.indexOf('=');
        return index < 0 ? segment : segment.substring(0, index);
    }

    private static String normalizeMethod(String httpMethod) {
        return nullToEmpty(httpMethod).trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
