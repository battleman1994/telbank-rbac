package com.telbank.rbac.sdk.client;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.telbank.rbac.sdk.config.RbacSdkProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 权限中心开放接口的默认实现（Boot 4 / Spring 7 版）：Spring {@link RestClient} + JDK HttpClient。
 *
 * <p>为保证签名与网络字节完全一致，这里显式构造完整 URL、原始 query 串与请求体字节，
 * 同一处计算签名并发送；响应以原始字节交给 Jackson 3 解码器（{@link RbacResponseDecoder}），
 * 不依赖宿主的 HttpMessageConverter。</p>
 *
 * <p>与 JDK 8 版 SDK 的协议（契约 §4.1/§4.2）、配置键、公开 API 完全一致。</p>
 */
public class HttpRbacOpenApiClient implements RbacOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRbacOpenApiClient.class);

    static final String PATH_MAPPINGS = "/openapi/sdk/mappings";

    static final String PATH_USER_PERMISSIONS = "/openapi/sdk/user-permissions";

    static final String PATH_PAGE_ACTIONS = "/openapi/sdk/page-actions";

    static final String PATH_CONNECTION = "/openapi/sdk/connection";

    static final String PATH_ENDPOINT_SYNC = "/openapi/sdk/endpoints/sync";

    static final String PATH_USER_SYSTEMS = "/openapi/sdk/user-systems";

    static final String PATH_ONBOARDING_SYSTEMS = "/openapi/onboarding/systems";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final RbacSdkProperties properties;

    private final RbacSigner signer;

    private final RestClient restClient;

    private final RbacResponseDecoder decoder;

    private final ObjectMapper objectMapper;

    private final String baseUrl;

    /** platform-base-url 自带的 path 前缀（通常为空），参与签名 path 计算。 */
    private final String basePath;

    public HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer) {
        this(properties, signer, createRestClient(), RbacResponseDecoder.createObjectMapper());
    }

    HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer, RestClient restClient,
            ObjectMapper objectMapper) {
        if (properties == null || signer == null || restClient == null || objectMapper == null) {
            throw new IllegalArgumentException("HttpRbacOpenApiClient 依赖不能为空");
        }
        this.properties = properties;
        this.signer = signer;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.decoder = new RbacResponseDecoder(objectMapper);
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
        } catch (RuntimeException e) {
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

    @Override
    public OnboardingApplyResult reportOnboarding(OnboardingApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("系统接入上报请求不能为空");
        }
        RbacSdkProperties.Onboarding onboarding = properties.getOnboarding();
        String onboardingAppKey = onboarding == null ? null : onboarding.getAppKey();
        String onboardingAppSecret = onboarding == null ? null : onboarding.getAppSecret();
        if (isBlank(onboardingAppKey) || isBlank(onboardingAppSecret)) {
            throw new IllegalArgumentException(
                    "缺少 rbac-sdk.onboarding.app-key / rbac-sdk.onboarding.app-secret");
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (RuntimeException e) {
            throw new RbacRemoteException("系统接入上报请求序列化失败", e);
        }
        // 使用 onboarding 专用 AppKey/AppSecret 签名，与业务系统主 AppKey 隔离
        return execute("POST", PATH_ONBOARDING_SYSTEMS, "", body, OnboardingApplyResult.class,
                new RbacSigner(onboardingAppKey, onboardingAppSecret));
    }

    private <T> T get(String path, Map<String, String> query, Type responseType) {
        return execute("GET", path, RbacSigner.buildQuery(query), null, responseType);
    }

    private <T> T execute(String httpMethod, String path, String rawQuery, byte[] body, Type responseType) {
        return execute(httpMethod, path, rawQuery, body, responseType, signer);
    }

    @SuppressWarnings("unchecked")
    private <T> T execute(String httpMethod, String path, String rawQuery, byte[] body, Type responseType,
            RbacSigner requestSigner) {
        String method = httpMethod.toUpperCase(Locale.ROOT);
        String requestUrl = baseUrl + path + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
        String signingPath = basePath + path;
        Map<String, String> signHeaders = requestSigner.signHeaders(method, signingPath, rawQuery, body);

        RawResponse rawResponse;
        try {
            RestClient.RequestBodySpec requestSpec = restClient.method(HttpMethod.valueOf(method))
                    .uri(URI.create(requestUrl));
            if (body != null) {
                requestSpec = requestSpec.body(body);
            }
            rawResponse = requestSpec
                    .headers(headers -> applyHeaders(headers, signHeaders, body))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] responseBody = response.getBody() == null
                                ? new byte[0]
                                : response.getBody().readAllBytes();
                        return new RawResponse(status, responseBody);
                    });
        } catch (RuntimeException e) {
            log.warn("调用权限中心失败: {} {} - {}", method, path, e.toString());
            throw new RbacRemoteException("调用权限中心失败(" + method + " " + path + "): " + e.getMessage(), e);
        }
        if (rawResponse == null) {
            throw new RbacRemoteException("权限中心无响应(" + method + " " + path + ")");
        }
        return (T) decoder.decode(rawResponse.statusCode, rawResponse.body, responseType);
    }

    private void applyHeaders(HttpHeaders headers, Map<String, String> signHeaders, byte[] body) {
        for (Map.Entry<String, String> entry : signHeaders.entrySet()) {
            headers.set(entry.getKey(), entry.getValue());
        }
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        String appVersion = properties.getAppVersion();
        if (!isBlank(appVersion)) {
            headers.set(HttpHeaders.USER_AGENT, "telbank-rbac-sdk/" + appVersion);
        }
    }

    private static Map<String, String> query(String key, String value) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put(key, value);
        return query;
    }

    private static RestClient createRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
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

    /**
     * RestClient exchange 返回的原始响应（状态码 + 响应体字节）。
     */
    private static final class RawResponse {

        private final int statusCode;

        private final byte[] body;

        private RawResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
