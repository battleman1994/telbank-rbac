package com.telbank.rbac.sdk.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.Response;
import feign.Util;
import feign.codec.Decoder;

/**
 * Feign 解码器：使用 Jackson 解析权限中心响应。
 *
 * <p>开放接口统一使用响应信封 {@code {"code":0,"message":"ok","data":<payload>}}，
 * SDK 只取 {@code data}；非 2xx 视为失败并抛出 {@link RbacRemoteException}。</p>
 */
class RbacFeignDecoder implements Decoder {

    private final ObjectMapper objectMapper;

    RbacFeignDecoder(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        byte[] body = readBody(response);
        int status = response.status();
        if (status < 200 || status >= 300) {
            throw new RbacRemoteException(status, buildErrorMessage(status, body));
        }
        if (type == Void.class || type == void.class) {
            return null;
        }
        if (body.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new RbacRemoteException("权限中心响应不是合法 JSON: " + snippet(body), e);
        }
        JsonNode data = root;
        if (root != null && root.isObject() && root.has("data")) {
            data = root.get("data");
        }
        if (data == null || data.isNull()) {
            return null;
        }
        JavaType javaType = objectMapper.getTypeFactory().constructType(type);
        try {
            return objectMapper.convertValue(data, javaType);
        } catch (IllegalArgumentException e) {
            throw new RbacRemoteException("权限中心响应结构与契约不一致: " + snippet(body), e);
        }
    }

    private static byte[] readBody(Response response) throws IOException {
        if (response.body() == null) {
            return new byte[0];
        }
        InputStream inputStream = response.body().asInputStream();
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } finally {
            try {
                inputStream.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private String buildErrorMessage(int status, byte[] body) {
        String message = null;
        if (body.length > 0) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root != null && root.has("message") && !root.get("message").isNull()) {
                    message = root.get("message").asText();
                }
            } catch (IOException ignored) {
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
        String text = Util.decodeOrDefault(body, Util.UTF_8, "");
        if (text.length() > 256) {
            text = text.substring(0, 256) + "...";
        }
        return text;
    }

    /**
     * 创建给内部/测试使用的基础 ObjectMapper。
     */
    static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
