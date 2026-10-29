package com.telbank.rbac.sdk.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

import feign.Client;
import feign.Feign;
import feign.FeignException;
import feign.Request;
import feign.codec.DecodeException;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;

/**
 * 权限中心开放接口的默认实现（JDK 8 / Spring Boot 2.7 版）：声明式 Feign 客户端 + Jackson 2。
 *
 * <p>接口协议集中声明在一个 {@link RbacOpenApiFeignApi} interface 类中；本类只做三件事：
 * 组装 Feign 代理（签名装饰器 + JSON 编解码器 + 超时）、调用接口方法、把 {@code data}
 * 泛型信封解包成业务 POJO。业务代码不再手工拼 URL / 构造请求 / 处理字节流。</p>
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

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private static final int READ_TIMEOUT_MILLIS = 8000;

    private static final int ERROR_BODY_SNIPPET_LENGTH = 256;

    private final RbacSdkProperties properties;

    private final RbacOpenApiFeignApi api;

    private final RbacOnboardingFeignApi onboardingApi;

    private final ObjectMapper objectMapper;

    public HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer) {
        this(properties, signer, createOkHttpClient(), createObjectMapper());
    }

    HttpRbacOpenApiClient(RbacSdkProperties properties, RbacSigner signer, Client delegate,
            ObjectMapper objectMapper) {
        if (properties == null || signer == null || delegate == null || objectMapper == null) {
            throw new IllegalArgumentException("HttpRbacOpenApiClient 依赖不能为空");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        String baseUrl = trimTrailingSlash(properties.getPlatformBaseUrl());
        this.api = buildProxy(RbacOpenApiFeignApi.class, delegate, signer, baseUrl);
        RbacSdkProperties.Onboarding onboarding = properties.getOnboarding();
        boolean onboardingConfigured = onboarding != null && !isBlank(onboarding.getAppKey())
                && !isBlank(onboarding.getAppSecret());
        this.onboardingApi = onboardingConfigured
                ? buildProxy(RbacOnboardingFeignApi.class, delegate,
                        new RbacSigner(onboarding.getAppKey(), onboarding.getAppSecret()), baseUrl)
                : null;
    }

    private <T> T buildProxy(Class<T> type, Client delegate, RbacSigner requestSigner, String baseUrl) {
        return Feign.builder()
                .client(new RbacSigningClient(delegate, requestSigner, properties.getAppVersion()))
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .options(new Request.Options(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS))
                .target(type, baseUrl);
    }

    @Override
    public List<MappingEntry> fetchMappings() {
        MappingsResponse response = invoke("GET " + PATH_MAPPINGS,
                () -> api.fetchMappings(properties.getSystemCode()));
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
        UserPermissionResponse response = invoke("GET " + PATH_USER_PERMISSIONS,
                () -> api.fetchUserPermissions(externalUserId, properties.getSystemCode()));
        if (response == null) {
            throw new RbacRemoteException("权限中心未返回用户权限集");
        }
        return response;
    }

    @Override
    public List<PageAction> fetchPageActions() {
        PageActionsResponse response = invoke("GET " + PATH_PAGE_ACTIONS,
                () -> api.fetchPageActions(properties.getSystemCode()));
        if (response == null || response.getPageActions() == null) {
            return Collections.emptyList();
        }
        return response.getPageActions();
    }

    @Override
    public ConnectionInfo fetchConnection() {
        ConnectionInfo connection = invoke("GET " + PATH_CONNECTION,
                () -> api.fetchConnection(properties.getSystemCode()));
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
        invoke("POST " + PATH_ENDPOINT_SYNC, () -> {
            api.syncEndpoints(request);
            return null;
        });
    }

    @Override
    public List<SystemInfo> fetchUserSystems(String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new IllegalArgumentException("externalUserId 不能为空");
        }
        List<SystemInfo> systems = invoke("GET " + PATH_USER_SYSTEMS,
                () -> api.fetchUserSystems(externalUserId));
        return systems == null ? Collections.<SystemInfo>emptyList() : systems;
    }

    @Override
    public OnboardingApplyResult reportOnboarding(OnboardingApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("系统接入上报请求不能为空");
        }
        if (onboardingApi == null) {
            throw new IllegalArgumentException(
                    "缺少 rbac-sdk.onboarding.app-key / rbac-sdk.onboarding.app-secret");
        }
        return invoke("POST " + PATH_ONBOARDING_SYSTEMS, () -> onboardingApi.reportOnboarding(request));
    }

    /**
     * 统一调用入口：解包 Feign 方法返回的 {@link RbacEnvelope}，并把 Feign/解码异常统一映射为
     * {@link RbacRemoteException}（网络失败 status=0，HTTP 失败保留状态码与服务端 message）。
     */
    private <T> T invoke(String operation, RemoteCall<RbacEnvelope<T>> call) {
        try {
            RbacEnvelope<T> envelope = call.call();
            return envelope == null ? null : envelope.getData();
        } catch (RbacRemoteException e) {
            throw e;
        } catch (DecodeException e) {
            throw new RbacRemoteException("权限中心响应不是合法 JSON: " + snippet(e.getMessage()), e);
        } catch (FeignException e) {
            if (e.status() > 0) {
                throw new RbacRemoteException(e.status(), buildErrorMessage(e.status(), e.contentUTF8()));
            }
            log.warn("调用权限中心失败: {} - {}", operation, e.toString());
            throw new RbacRemoteException("调用权限中心失败(" + operation + "): " + e.getMessage(), e);
        }
    }

    private String buildErrorMessage(int status, String body) {
        String message = null;
        if (body != null && !body.isEmpty()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root != null && root.has("message") && !root.get("message").isNull()) {
                    message = root.get("message").asText();
                }
            } catch (JsonProcessingException ignored) {
                // 非 JSON 响应，使用原始片段
            }
        }
        if (message == null || message.trim().isEmpty()) {
            message = snippet(body);
        }
        return "权限中心返回 HTTP " + status + (message.isEmpty() ? "" : ": " + message);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() > ERROR_BODY_SNIPPET_LENGTH) {
            return trimmed.substring(0, ERROR_BODY_SNIPPET_LENGTH) + "...";
        }
        return trimmed;
    }

    static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    static Client createOkHttpClient() {
        okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
        return new feign.okhttp.OkHttpClient(okHttpClient);
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

    /** 允许 lambda 调用 Feign 代理方法（无受检异常语义）的最小函数式接口。 */
    private interface RemoteCall<T> {

        T call();
    }
}
