package com.telbank.rbac.sdk.client;

import java.lang.reflect.Type;

import java.nio.charset.StandardCharsets;

import feign.RequestTemplate;
import feign.codec.EncodeException;

/**
 * Feign 编码器（Jackson 3 版）：把 {@code @Body} 业务 POJO 序列化为 JSON 请求体。
 *
 * <p>SDK 使用 Jackson 3（{@code tools.jackson}），与宿主的 Boot 4 技术栈一致；
 * 编码后的字节即 Feign 实际发送的 body，{@link RbacSigningClient} 直接对其签名。</p>
 */
final class RbacJacksonEncoder implements feign.codec.Encoder {

    private final tools.jackson.databind.ObjectMapper objectMapper;

    RbacJacksonEncoder(tools.jackson.databind.ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.objectMapper = objectMapper;
    }

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) {
        if (object == null) {
            return;
        }
        try {
            template.body(objectMapper.writeValueAsBytes(object), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new EncodeException("请求序列化失败: " + e.getMessage(), e);
        }
    }
}
