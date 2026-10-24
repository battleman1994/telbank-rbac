package com.telbank.rbac.sdk.client;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 权限中心响应解码器（Boot 4 / Jackson 3 版）。
 *
 * <p>开放接口统一使用响应信封 {@code {"code":0,"message":"ok","data":<payload>}}，
 * SDK 只取 {@code data}；非 2xx 视为失败并抛出 {@link RbacRemoteException}。</p>
 */
class RbacResponseDecoder {

    private static final int SNIPPET_LENGTH = 256;

    private final ObjectMapper objectMapper;

    RbacResponseDecoder(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * 解码响应。
     *
     * @param status HTTP 状态码
     * @param body   响应体原始字节
     * @param type   期望的 data 类型（{@link Void} 表示不关心响应体）
     * @return data 反序列化结果，可能为 null
     */
    Object decode(int status, byte[] body, Type type) {
        if (status < 200 || status >= 300) {
            throw new RbacRemoteException(status, buildErrorMessage(status, body));
        }
        if (type == Void.class || type == void.class) {
            return null;
        }
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new RbacRemoteException("权限中心响应不是合法 JSON: " + snippet(body), e);
        }
        JsonNode data = root;
        if (root != null && root.isObject() && root.has("data")) {
            data = root.get("data");
        }
        if (data == null || data.isNull()) {
            return null;
        }
        JavaType javaType = objectMapper.constructType(type);
        try {
            return objectMapper.convertValue(data, javaType);
        } catch (IllegalArgumentException e) {
            throw new RbacRemoteException("权限中心响应结构与契约不一致: " + snippet(body), e);
        }
    }

    private String buildErrorMessage(int status, byte[] body) {
        String message = null;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root != null && root.has("message") && !root.get("message").isNull()) {
                    message = root.get("message").asText();
                }
            } catch (JacksonException ignored) {
                // 非 JSON 响应，使用原始片段
            }
        }
        if (message == null || message.trim().isEmpty()) {
            message = snippet(body);
        }
        return "权限中心返回 HTTP " + status + (message.isEmpty() ? "" : ": " + message);
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.length() > SNIPPET_LENGTH) {
            text = text.substring(0, SNIPPET_LENGTH) + "...";
        }
        return text;
    }

    /**
     * 创建 SDK 内部使用的 Jackson 3 ObjectMapper（忽略未知字段，兼容权限中心扩展字段）。
     */
    static ObjectMapper createObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
