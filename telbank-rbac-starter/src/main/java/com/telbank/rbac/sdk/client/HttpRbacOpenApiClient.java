package com.telbank.rbac.sdk.client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 权限中心开放接口的默认实现（JDK 8 / Spring Boot 2.7 版）：Spring {@link RestTemplate} + Jackson 2。
 *
 * <p>客户端方法只收发业务 POJO；HTTP 细节（URL、query、JSON 编解码、状态码）全部交给
 * {@link RestTemplate} 与 Jackson 消息转换器。唯一保留在传输层的是「把请求 POJO 序列化一次」：
 * 开放接口用 HMAC-SHA256 对实际发送的请求体签名，必须保证签名与线上字节一致。</p>
 *
 * <p>与 JDK 21 版 SDK 的协议（契约 §4.1/§4.2）、配置键、公开 API 完全一致。</p>
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

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private static final int READ_TIMEOUT_MILLIS = 8000;

    private static final int ERROR_BODY_SNIPPET_LENGTH = 256;

    private static final ParameterizedTypeReference<RbacEnvelope<MappingsResponse>> MAPPINGS_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<MappingsResponse>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<UserPermissionResponse>> USER_PERMISSIONS_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<UserPermissionResponse>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<PageActionsResponse>> PAGE_ACTIONS_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<PageActionsResponse>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<ConnectionInfo>> CONNECTION_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<ConnectionInfo>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<Object>> ENDPOINT_SYNC_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<Object>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<List<SystemInfo>>> USER_SYSTEMS_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<List<SystemInfo>>>() {
            };

    private static final ParameterizedTypeReference<RbacEnvelope<OnboardingApplyResult>> ONBOARDING_TYPE =
            new ParameterizedTypeReference<RbacEnvelope<OnboardingApplyResult>>() {
            };

    private final RbacSdkProperties properties;

    private final RbacSigner signer;

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    private final String baseUrl;

    /** platform-base-url 自带的 path 前缀（通常为空），参与签名 path 计算。 */
    private final String basePath;

    public HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer) {
        this(properties, signer, createObjectMapper());
    }

    HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer, ObjectMapper objectMapper) {
        this(properties, signer, createRestTemplate(objectMapper), objectMapper);
    }

    HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer, RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        if (properties == null || signer == null || restTemplate == null || objectMapper == null) {
            throw new IllegalArgumentException("HttpRbacOpenApiClient 依赖不能为空");
        }
        this.properties = properties;
        this.signer = signer;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(properties.getPlatformBaseUrl());
        this.basePath = resolveBasePath(this.baseUrl);
    }

    @Override
    public List<MappingEntry> fetchMappings() {
        MappingsResponse response = get(PATH_MAPPINGS, query("systemCode", properties.getSystemCode()),
                MAPPINGS_TYPE);
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
        UserPermissionResponse response = get(PATH_USER_PERMISSIONS,
                query("systemCode", properties.getSystemCode(), "externalUserId", externalUserId),
                USER_PERMISSIONS_TYPE);
        if (response == null) {
            throw new RbacRemoteException("权限中心未返回用户权限集");
        }
        return response;
    }

    @Override
    public List<PageAction> fetchPageActions() {
        PageActionsResponse response = get(PATH_PAGE_ACTIONS, query("systemCode", properties.getSystemCode()),
                PAGE_ACTIONS_TYPE);
        if (response == null || response.getPageActions() == null) {
            return Collections.emptyList();
        }
        return response.getPageActions();
    }

    @Override
    public ConnectionInfo fetchConnection() {
        ConnectionInfo connection = get(PATH_CONNECTION, query("systemCode", properties.getSystemCode()),
                CONNECTION_TYPE);
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
        post(PATH_ENDPOINT_SYNC, request, ENDPOINT_SYNC_TYPE, signer);
    }

    @Override
    public List<SystemInfo> fetchUserSystems(String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new IllegalArgumentException("externalUserId 不能为空");
        }
        List<SystemInfo> systems = get(PATH_USER_SYSTEMS, query("externalUserId", externalUserId),
                USER_SYSTEMS_TYPE);
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
        // 使用 onboarding 专用 AppKey/AppSecret 签名，与业务系统主 AppKey 隔离
        return post(PATH_ONBOARDING_SYSTEMS, request, ONBOARDING_TYPE,
                new RbacSigner(onboardingAppKey, onboardingAppSecret));
    }

    private <T> T get(String path, Map<String, String> query,
            ParameterizedTypeReference<RbacEnvelope<T>> responseType) {
        return execute(HttpMethod.GET, path, RbacSigner.buildQuery(query), null, responseType, signer);
    }

    private <T> T post(String path, Object payload,
            ParameterizedTypeReference<RbacEnvelope<T>> responseType, RbacSigner requestSigner) {
        return execute(HttpMethod.POST, path, "", payload, responseType, requestSigner);
    }

    private <T> T execute(HttpMethod method, String path, String rawQuery, Object payload,
            ParameterizedTypeReference<RbacEnvelope<T>> responseType, RbacSigner requestSigner) {
        String requestUrl = baseUrl + path + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
        String signingPath = basePath + path;
        byte[] body = serialize(payload);
        Map<String, String> signHeaders = requestSigner.signHeaders(method.name(), signingPath, rawQuery, body);

        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, String> entry : signHeaders.entrySet()) {
            headers.set(entry.getKey(), entry.getValue());
        }
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(new MediaType("application", "json", UTF_8));
        }
        String appVersion = properties.getAppVersion();
        if (!isBlank(appVersion)) {
            headers.set(HttpHeaders.USER_AGENT, "telbank-rbac-sdk/" + appVersion);
        }

        try {
            ResponseEntity<RbacEnvelope<T>> response = restTemplate.exchange(
                    requestUrl, method, new HttpEntity<byte[]>(body, headers), responseType);
            RbacEnvelope<T> envelope = response.getBody();
            return envelope == null ? null : envelope.getData();
        } catch (HttpStatusCodeException e) {
            throw new RbacRemoteException(e.getStatusCode().value(),
                    buildErrorMessage(e.getStatusCode().value(), e.getResponseBodyAsByteArray()));
        } catch (ResourceAccessException e) {
            log.warn("调用权限中心失败: {} {} - {}", method, path, e.toString());
            throw new RbacRemoteException("调用权限中心失败(" + method + " " + path + "): " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.warn("权限中心响应解析失败: {} {} - {}", method, path, e.toString());
            throw new RbacRemoteException("权限中心响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 请求 POJO → JSON 字节：签名（HMAC over body）与线上发送必须使用同一份字节，因此只序列化一次。
     */
    private byte[] serialize(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new RbacRemoteException("请求序列化失败: " + e.getMessage(), e);
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
        String text = new String(body, UTF_8);
        if (text.length() > ERROR_BODY_SNIPPET_LENGTH) {
            text = text.substring(0, ERROR_BODY_SNIPPET_LENGTH) + "...";
        }
        return text;
    }

    private static Map<String, String> query(String key, String value) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put(key, value);
        return query;
    }

    private static Map<String, String> query(String key1, String value1, String key2, String value2) {
        Map<String, String> query = query(key1, value1);
        query.put(key2, value2);
        return query;
    }

    static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    static RestTemplate createRestTemplate(ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        RestTemplate template = new RestTemplate(requestFactory);
        template.setMessageConverters(Arrays.<HttpMessageConverter<?>>asList(
                new ByteArrayHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter(objectMapper)));
        return template;
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
