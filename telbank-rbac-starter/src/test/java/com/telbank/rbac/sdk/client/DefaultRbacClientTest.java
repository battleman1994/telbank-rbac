package com.telbank.rbac.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacMappingCache;
import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * {@link RbacClient} 默认实现的系统列表语义（v2.0 系统隔离）：原样透传角色推导结果，
 * 空列表（用户无有效角色）不降级；仅回源失败时降级为本系统单例。
 */
class DefaultRbacClientTest {

    @Test
    void getSystemsPassesThroughRoleDerivedSystems() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.systems.add(StubRbacOpenApiClient.system("crm", "CRM系统", true));
        remote.systems.add(StubRbacOpenApiClient.system("erp", "ERP系统", false));

        List<SystemInfo> systems = client(remote).getSystems("13600136000");

        assertThat(systems).hasSize(2);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(systems.get(0).getIsDefault()).isTrue();
        assertThat(systems.get(1).getCode()).isEqualTo("erp");
        assertThat(systems.get(1).getIsDefault()).isFalse();
        assertThat(remote.lastExternalUserId).isEqualTo("13600136000");
        assertThat(remote.systemCalls.get()).isEqualTo(1);
    }

    @Test
    void getSystemsReturnsEmptyListWhenUserHasNoValidRole() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();

        List<SystemInfo> systems = client(remote).getSystems("13700137000");

        assertThat(systems).isEmpty();
        assertThat(remote.systemCalls.get()).isEqualTo(1);
    }

    @Test
    void getSystemsFallsBackToLocalSystemWhenPermissionCenterDown() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnSystems = new RbacRemoteException("down");

        List<SystemInfo> systems = client(remote).getSystems("13600136000");

        assertThat(systems).hasSize(1);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(systems.get(0).getName()).isEqualTo("crm");
        assertThat(systems.get(0).getIsDefault()).isTrue();
    }

    @Test
    void getSystemsFallsBackForBlankUserWithoutRemoteCall() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();

        List<SystemInfo> systems = client(remote).getSystems("  ");

        assertThat(systems).hasSize(1);
        assertThat(systems.get(0).getCode()).isEqualTo("crm");
        assertThat(remote.systemCalls.get()).isZero();
    }

    @Test
    void getPermissionsRejectsOtherSystemCode() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();

        assertThatThrownBy(() -> client(remote).getPermissions("13600136000", "erp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("erp");
    }

    @Test
    void getPermissionsKeepsWildcardEntries() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        long now = System.currentTimeMillis();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:*", null, null));

        assertThat(client(remote).getPermissions("13600136000", "crm")).containsExactly("order:*");
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static DefaultRbacClient client(StubRbacOpenApiClient remote) {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("https://rbac.example.com");
        properties.setAppKey("crm");
        properties.setAppSecret("secret");
        RbacMappingCache mappingCache = new RbacMappingCache();
        RbacPageActionCache pageActionCache = new RbacPageActionCache();
        RbacCacheRefresher cacheRefresher = new RbacCacheRefresher(remote, mappingCache,
                pageActionCache);
        RbacUserPermissionCache userPermissionCache = new RbacUserPermissionCache(remote);
        return new DefaultRbacClient(remote, properties, userPermissionCache, pageActionCache,
                cacheRefresher);
    }
}
