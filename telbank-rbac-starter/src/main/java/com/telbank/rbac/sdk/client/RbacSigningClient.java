package com.telbank.rbac.sdk.client;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import feign.Client;
import feign.Request;
import feign.Response;
import feign.Util;

/**
 * Feign 客户端装饰器：在最终请求上注入开放接口签名头。
 *
 * <p>签名必须覆盖「实际发送」的 method / path / 原始 query / 请求体字节，
 * 因此放在 Feign {@link Client} 这一层（此时 Feign 已完成 URL 与 body 编码），
 * 业务接口方法只描述协议，不接触签名或字节。</p>
 */
final class RbacSigningClient implements Client {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final Client delegate;

    private final RbacSigner signer;

    private final String appVersion;

    RbacSigningClient(Client delegate, RbacSigner signer, String appVersion) {
        if (delegate == null || signer == null) {
            throw new IllegalArgumentException("RbacSigningClient 依赖不能为空");
        }
        this.delegate = delegate;
        this.signer = signer;
        this.appVersion = appVersion;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        URI uri = URI.create(request.url());
        Map<String, String> signHeaders = signer.signHeaders(request.method(), uri.getPath(),
                uri.getRawQuery(), request.body());

        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>(request.headers());
        for (Map.Entry<String, String> entry : signHeaders.entrySet()) {
            headers.put(entry.getKey(), Collections.singletonList(entry.getValue()));
        }
        headers.put("Accept", Collections.singletonList("application/json"));
        if (appVersion != null && !appVersion.trim().isEmpty()) {
            headers.put("User-Agent", Collections.singletonList("telbank-rbac-sdk/" + appVersion));
        }

        Request signed = Request.create(request.method(), request.url(), headers, request.body(), UTF_8);
        return delegate.execute(signed, options);
    }
}
