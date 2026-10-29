package com.telbank.rbac.sdk.client;

import java.io.IOException;
import java.lang.reflect.Type;

import feign.FeignException;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;

/**
 * Feign 解码器（Jackson 3 版）：把开放接口响应反序列化为泛型 {@link RbacEnvelope} POJO。
 *
 * <p>非 2xx 由 Feign 的错误解码器处理；本类只负责 2xx 的 JSON 反序列化，
 * 不手写字节流或 JSON 树遍历。</p>
 */
final class RbacJacksonDecoder implements Decoder {

    private final tools.jackson.databind.ObjectMapper objectMapper;

    RbacJacksonDecoder(tools.jackson.databind.ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException, DecodeException, FeignException {
        if (response.body() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(response.body().asInputStream(), objectMapper.constructType(type));
        } catch (RuntimeException e) {
            throw new DecodeException(response.status(), "权限中心响应不是合法 JSON", response.request(), e);
        }
    }
}
