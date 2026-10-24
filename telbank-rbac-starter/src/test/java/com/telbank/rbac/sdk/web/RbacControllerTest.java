package com.telbank.rbac.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.PageAction;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.client.SystemInfo;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * /rbac/me/systems 与 /rbac/me/bootstrap：身份获取、预热、平台超管、降级。
 */
class RbacControllerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String USER = "13600136000";

    @Test
    void systemsUsesOpenApiUserSystems() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.systems.add(StubRbacOpenApiClient.system("crm", "CRM系统", true));
        RbacController controller = controller(remote, new RbacPageActionCache());

        List<SystemInfo> systems = controller.getSystems(requestWithJwt());

        assertThat(systems).hasSize(1);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(remote.lastExternalUserId).isEqualTo(USER);
    }

    @Test
    void systemsFallsBackToLocalSystemWhenPermissionCenterDown() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnSystems = new RbacRemoteException("down");
        RbacController controller = controller(remote, new RbacPageActionCache());

        List<SystemInfo> systems = controller.getSystems(requestWithJwt());

        assertThat(systems).hasSize(1);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(systems.get(0).getIsDefault()).isTrue();
    }

    @Test
    void bootstrapReturnsValidPermissionsAndPrewarmsCache() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        long now = System.currentTimeMillis();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:list", null, null),
                StubRbacOpenApiClient.permission("order:delete", iso(now), iso(now + 600_000L)));
        RbacController controller = controller(remote, new RbacPageActionCache());

        RbacController.BootstrapResponse response = controller.getBootstrap(requestWithJwt());

        assertThat(response.getPermissions()).containsExactlyInAnyOrder("order:list", "order:delete");
        assertThat(response.isPlatformAdmin()).isFalse();
        assertThat(remote.userPermissionCalls.get()).isEqualTo(1);

        // 预热后再次 bootstrap 直接命中缓存，不再回源
        controller.getBootstrap(requestWithJwt());
        assertThat(remote.userPermissionCalls.get()).isEqualTo(1);
    }

    @Test
    void bootstrapReturnsWildcardForPlatformAdmin() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("*", null, null));
        RbacController controller = controller(remote, new RbacPageActionCache());

        RbacController.BootstrapResponse response = controller.getBootstrap(requestWithJwt());

        assertThat(response.isPlatformAdmin()).isTrue();
        assertThat(response.getPermissions()).containsExactly("*");
    }

    @Test
    void bootstrapReturnsCachedPageActions() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:list", null, null));
        RbacPageActionCache pageActionCache = new RbacPageActionCache();
        PageAction action = StubRbacOpenApiClient.pageAction("order:list", "order:create");
        pageActionCache.replace(Collections.singletonList(action));
        RbacController controller = controller(remote, pageActionCache);

        RbacController.BootstrapResponse response = controller.getBootstrap(requestWithJwt());

        assertThat(response.getPageActions()).containsExactly(action);
        assertThat(response.getPermissions()).containsExactly("order:list");
    }

    @Test
    void bootstrapReturnsEmptyForAnonymous() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacPageActionCache pageActionCache = new RbacPageActionCache();
        pageActionCache.replace(Collections.singletonList(
                StubRbacOpenApiClient.pageAction("order:list", "order:create")));
        RbacController controller = controller(remote, pageActionCache);

        RbacController.BootstrapResponse response = controller.getBootstrap(
                new MockHttpServletRequest("GET", "/rbac/me/bootstrap"));

        assertThat(response.getPermissions()).isEmpty();
        assertThat(response.getPageActions()).isEmpty();
        assertThat(response.isPlatformAdmin()).isFalse();
        assertThat(remote.userPermissionCalls.get()).isZero();
    }

    @Test
    void bootstrapReturnsEmptyPermissionsWhenPermissionCenterDown() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnUserPermissions = new RbacRemoteException("down");
        RbacController controller = controller(remote, new RbacPageActionCache());

        RbacController.BootstrapResponse response = controller.getBootstrap(requestWithJwt());

        assertThat(response.getPermissions()).isEmpty();
        assertThat(response.isPlatformAdmin()).isFalse();
    }

    private static RbacController controller(StubRbacOpenApiClient remote,
            RbacPageActionCache pageActionCache) {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("https://rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("secret");
        return new RbacController(properties, remote,
                new RbacUserPermissionCache(remote), pageActionCache);
    }

    private static MockHttpServletRequest requestWithJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rbac/me/bootstrap");
        request.addHeader("Authorization", "Bearer " + jwt(USER));
        return request;
    }

    private static String jwt(String subject) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payload + ".test-signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
