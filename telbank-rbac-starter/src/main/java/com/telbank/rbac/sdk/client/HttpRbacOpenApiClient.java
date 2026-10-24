package com.telbank.rbac.sdk.client;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

import feign.Request;
import feign.Response;
import feign.Util;
import okhttp3.OkHttpClient;

/**
 * 权限中心开放接口的默认实现：Feign（{@code feign.okhttp.OkHttpClient} 适配器）+ OkHttp。
 *
 * <p>为保证签名与网络字节完全一致，这里采用契约允许的「显式构造 {@link feign.Request}」方式，
 * 而不是 Feign 接口 + RequestInterceptor：请求 path / 原始 query 串 / body 字节都在同一处生成，
 * 签名与发送不可能出现编码差异。响应统一由 {@link RbacFeignDecoder}（Jackson）解码。</p>
 */
public class HttpRbacOpenApiClient implements RbacOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRbacOpenApiClient.class);

    static final String PATH_MAPPINGS = "/openapi/sdk/mappings";

    static final String PATH_USER_PERMISSIONS = "/openapi/sdk/user-permissions";

    static final String PATH_PAGE_ACTIONS = "/openapi/sdk/page-actions";

    static final String PATH_CONNECTION = "/openapi/sdk/connection";

    static final String PATH_ENDPOINT_SYNC = "/openapi/sdk/endpoints/sync";

    static final String PATH_USER_SYSTEMS = "/openapi/sdk/user-systems";

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private static final int READ_TIMEOUT_MILLIS = 8000;

    private final RbacSdkProperties properties;

    private final RbacSigner signer;

    private final feign.Client client;

    private final RbacFeignDecoder decoder;

    private final ObjectMapper objectMapper;

    private final Request.Options options = new Request.Options(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);

    private final String baseUrl;

    /** platform-base-url 自带的 path 前缀（通常为空），参与签名 path 计算。 */
    private final String basePath;

    public HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer) {
        this(properties, signer, createFeignClient(), RbacFeignDecoder.createObjectMapper());
    }

    HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer, feign.Client client,
            ObjectMapper objectMapper) {
        if (properties == null || signer == null || client == null || objectMapper == null) {
            throw new IllegalArgumentException("HttpRbacOpenApiClient 依赖不能为空");
        }
        this.properties = properties;
        this.signer = signer;
        this.client = client;
        this.objectMapper = objectMapper;
        this.decoder = new RbacFeignDecoder(objectMapper);
        this.baseUrl = trimTrailingSlash(properties.getPlatformBaseUrl());
        this.basePath = resolveBasePath(this.baseUrl);
    }

    @Override
    public List<MappingEntry> fetchMappings() {
        MappingsResponse response = get(PATH_MAPPINGS, query("systemCode", properties.getSystemCode()),
                MappingsResponse.class);
        if (response == null || response.getMappings() == null) {
            return Collections.emptyList();
        }
        return response.getMappings();
    }

    @Override
    public UserPermissionResponse fetchUserPermissions(String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new IllegalArgumentException("externalUserId 不能为空");
        }
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("systemCode", properties.getSystemCode());
        query.put("externalUserId", externalUserId);
        UserPermissionResponse response = get(PATH_USER_PERMISSIONS, query, UserPermissionResponse.class);
        if (response == null) {
            throw new RbacRemoteException("权限中心未返回用户权限集");
        }
        return response;
    }

    @Override
    public List<PageAction> fetchPageActions() {
        PageActionsResponse response = get(PATH_PAGE_ACTIONS, query("systemCode", properties.getSystemCode()),
                PageActionsResponse.class);
        if (response == null || response.getPageActions() == null) {
            return Collections.emptyList();
        }
        return response.getPageActions();
    }

    @Override
    public ConnectionInfo fetchConnection() {
        ConnectionInfo connection = get(PATH_CONNECTION, query("systemCode", properties.getSystemCode()),
                ConnectionInfo.class);
        if (connection == null) {
            throw new RbacRemoteException("权限中心未返回 Redis 连接信息");
        }
        if (isBlank(connection.getHost())) {
            throw new RbacRemoteException("Redis 连接信息缺少 host");
        }
        if (connection.getPort() == null || connection.getPort() <= 0 || connection.getPort() > 65535) {
            throw new RbacRemoteException("Redis 连接信息端口非法: " + connection.getPort());
        }
        return connection;
    }

    @Override
    public void syncEndpoints(List<EndpointInfo> endpoints) {
        EndpointSyncRequest request = new EndpointSyncRequest(properties.getSystemCode(),
                endpoints == null ? Collections.<EndpointInfo>emptyList() : endpoints);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new RbacRemoteException("端点上报请求序列化失败", e);
        }
        execute("POST", PATH_ENDPOINT_SYNC, "", body, Void.class);
    }

    @Override
    public List<SystemInfo> fetchUserSystems(String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new IllegalArgumentException("externalUserId 不能为空");
        }
        Type type = new TypeReference<List<SystemInfo>>() {
        }.getType();
        List<SystemInfo> systems = get(PATH_USER_SYSTEMS, query("externalUserId", externalUserId), type);
        return systems == null ? Collections.<SystemInfo>emptyList() : systems;
    }

    private <T> T get(String path, Map<String, String> query, Type responseType) {
        return execute("GET", path, RbacSigner.buildQuery(query), null, responseType);
    }

    @SuppressWarnings("unchecked")
    private <T> T execute(String httpMethod, String path, String rawQuery, byte[] body, Type responseType) {
        String method = httpMethod.toUpperCase(Locale.ROOT);
        String requestUrl = baseUrl + path + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
        String signingPath = basePath + path;
        Map<String, String> signHeaders = signer.signHeaders(method, signingPath, rawQuery, body);
        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
        for (Map.Entry<String, String> entry : signHeaders.entrySet()) {
            headers.put(entry.getKey(), Collections.singletonList(entry.getValue()));
        }
        headers.put("Accept", Collections.singletonList("application/json"));
        if (body != null) {
            headers.put("Content-Type", Collections.singletonList("application/json;charset=UTF-8"));
        }
        String appVersion = properties.getAppVersion();
        if (!isBlank(appVersion)) {
            headers.put("User-Agent", Collections.singletonList("telbank-rbac-sdk/" + appVersion));
        }

        Request request = Request.create(method, requestUrl, headers, body, Util.UTF_8);
        Response response = null;
        try {
            response = client.execute(request, options);
            return (T) decoder.decode(response, responseType);
        } catch (RbacRemoteException e) {
            throw e;
        } catch (IOException e) {
            log.warn("调用权限中心失败: {} {} - {}", method, path, e.toString());
            throw new RbacRemoteException("调用权限中心失败(" + method + " " + path + "): " + e.getMessage(), e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
        }
    }

    private static Map<String, String> query(String key, String value) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put(key, value);
        return query;
    }

    private static feign.Client createFeignClient() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
        return new feign.okhttp.OkHttpClient(okHttpClient);
    }

    private static String resolveBasePath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            if (path == null) {
                return "";
            }
            path = trimTrailingSlash(path);
            return "/".equals(path) ? "" : path;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.length() > 0 && result.charAt(result.length() - 1) == '/') {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
