package com.telbank.rbac.sdk.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.telbank.rbac.sdk.cache.RbacMappingCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.MappingEntry;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * 拦截器关键分支：未命中放行 / deny 策略 / 命中无权限 403 / 匿名 403 / 回源失败用旧缓存 /
 * 基础设施路径与 SDK 自身路径放行 / 内部异常放行。
 */
class RbacPermissionFilterTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String USER = "13600136000";

    @Test
    void unmatchedRequestPassesWithoutQueryingUserPermissions() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                requestWithUser("GET", "/api/health"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void unmatchedRequestReturns403WhenDenyStrategy() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(true), mappings, userCache(remote)).doFilter(
                requestWithUser("GET", "/api/health"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":403").contains("message");
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void matchedRequestWithoutPermissionReturns403Json() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(now(),
                StubRbacOpenApiClient.permission("order:list", null, null));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                requestWithUser("DELETE", "/api/order/123"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":403").contains("order:delete");
    }

    @Test
    void matchedRequestWithValidPermissionPasses() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(now(),
                StubRbacOpenApiClient.permission("order:delete", null, null));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                requestWithUser("DELETE", "/api/order/123"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(remote.lastExternalUserId).isEqualTo(USER);
    }

    @Test
    void matchedRequestWithExpiredPermissionReturns403() throws Exception {
        long nowMillis = System.currentTimeMillis();
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(now(),
                StubRbacOpenApiClient.permission("order:delete", null,
                        Instant.ofEpochMilli(nowMillis - 1_000L).toString()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                requestWithUser("DELETE", "/api/order/123"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void matchedRequestOfAnonymousUserReturns403() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("GET", "/api/order/list", "order:list"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                new MockHttpServletRequest("GET", "/api/order/list"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("anonymous");
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void identityFallsBackToJwtBearerSubject() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("GET", "/api/order/list", "order:list"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(now(),
                StubRbacOpenApiClient.permission("order:list", null, null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/order/list");
        request.addHeader("Authorization", "Bearer " + jwt(USER));
        MockFilterChain chain = new MockFilterChain();

        filter(properties(false), mappings, userCache(remote)).doFilter(request,
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(remote.lastExternalUserId).isEqualTo(USER);
    }

    @Test
    void defaultInfrastructurePathsAreExcluded() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("GET", "/actuator/health", "admin:monitor"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();

        filter(properties(true), mappings, userCache(remote)).doFilter(
                new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void sdkOwnBasePathIsAlwaysExcludedEvenWithDenyStrategy() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("GET", "/rbac/me/bootstrap", "some:code"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();

        filter(properties(true), mappings, userCache(remote)).doFilter(
                new MockHttpServletRequest("GET", "/rbac/me/bootstrap"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void staleCacheIsUsedWhenRemoteFails() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(now(),
                StubRbacOpenApiClient.permission("order:delete", null, null));
        RbacUserPermissionCache userCache = userCache(remote);
        RbacPermissionFilter filter = filter(properties(false), mappings, userCache);

        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(requestWithUser("DELETE", "/api/order/123"), new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.getRequest()).isNotNull();
        assertThat(remote.userPermissionCalls.get()).isEqualTo(1);

        userCache.invalidateAll();
        remote.failOnUserPermissions = new RbacRemoteException("permission center down");
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilter(requestWithUser("DELETE", "/api/order/123"), new MockHttpServletResponse(), secondChain);

        assertThat(secondChain.getRequest()).isNotNull();
    }

    @Test
    void remoteFailureWithoutCachePassesByDefaultStrategy() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnUserPermissions = new RbacRemoteException("permission center down");
        MockFilterChain chain = new MockFilterChain();

        filter(properties(false), mappings, userCache(remote)).doFilter(
                requestWithUser("DELETE", "/api/order/123"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void remoteFailureWithoutCacheReturns403UnderDenyStrategy() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnUserPermissions = new RbacRemoteException("permission center down");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(true), mappings, userCache(remote)).doFilter(
                requestWithUser("DELETE", "/api/order/123"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void internalExceptionPassesForAvailability() throws Exception {
        RbacMappingCache brokenMappings = new RbacMappingCache() {
            @Override
            public String match(String httpMethod, String path) {
                throw new IllegalStateException("boom");
            }
        };
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(true), brokenMappings, userCache(remote)).doFilter(
                requestWithUser("GET", "/api/order/list"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void corsPreflightPassesWithoutPermissionCheck() throws Exception {
        RbacMappingCache mappings = mappingCache(new MappingEntry("DELETE", "/api/order/{id}", "order:delete"));
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        MockFilterChain chain = new MockFilterChain();

        filter(properties(true), mappings, userCache(remote)).doFilter(
                new MockHttpServletRequest("OPTIONS", "/api/order/123"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    private static RbacSdkProperties properties(boolean denyUnmatched) {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("https://rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("crm-dev-secret");
        if (denyUnmatched) {
            properties.getInterceptor().setUnmatchedStrategy("deny");
        }
        return properties;
    }

    private static RbacPermissionFilter filter(RbacSdkProperties properties, RbacMappingCache mappings,
            RbacUserPermissionCache userCache) {
        return new RbacPermissionFilter(properties, mappings, userCache);
    }

    private static RbacUserPermissionCache userCache(StubRbacOpenApiClient remote) {
        return new RbacUserPermissionCache(remote);
    }

    private static RbacMappingCache mappingCache(MappingEntry... entries) {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(entries == null ? Collections.<MappingEntry>emptyList() : Arrays.asList(entries));
        return cache;
    }

    private static MockHttpServletRequest requestWithUser(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setUserPrincipal(new java.security.Principal() {
            @Override
            public String getName() {
                return USER;
            }
        });
        return request;
    }

    private static String now() {
        return Instant.ofEpochMilli(System.currentTimeMillis()).toString();
    }

    private static String jwt(String subject) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payload + ".test-signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
    }
}
