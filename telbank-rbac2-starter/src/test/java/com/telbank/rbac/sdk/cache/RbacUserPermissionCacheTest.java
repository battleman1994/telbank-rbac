package com.telbank.rbac.sdk.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.client.UserPermissionEntry;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * 用户权限集缓存：时效判定（validFrom/validUntil/null）、时钟偏移、失效与回源降级。
 */
class RbacUserPermissionCacheTest {

    private static final String USER = "13600136000";

    private static final String SYSTEM = "crm";

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static RbacUserPermissionCache newCache(StubRbacOpenApiClient remote) {
        return new RbacUserPermissionCache(remote);
    }

    @Test
    void permissionWithoutValidityWindowIsAllowed() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:delete", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.getValidPermissions(USER, SYSTEM)).containsExactly("order:delete");
        // 第二次判定命中缓存，不再回源
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(remote.userPermissionCalls.get()).isEqualTo(1);
    }

    @Test
    void missingCodeIsDenied() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:list", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
    }

    @Test
    void expiredValidUntilIsDeniedByLocalClock() {
        long now = System.currentTimeMillis();
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:delete", null, iso(now - 60_000L)));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        assertThat(cache.getValidPermissions(USER, SYSTEM)).isEmpty();
    }

    @Test
    void futureValidFromIsDenied() {
        long now = System.currentTimeMillis();
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:delete", iso(now + 3_600_000L), null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
    }

    @Test
    void serverClockOffsetIsAppliedWhenCheckingValidity() {
        long now = System.currentTimeMillis();
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        // 权限中心时钟比业务系统快 1 小时：validUntil 本地看是 30 分钟后，但按服务端时间已过期
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now + 3_600_000L),
                StubRbacOpenApiClient.permission("order:delete", null, iso(now + 1_800_000L)));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        // 每次回源重算偏移：服务端时间已追上后，同一 validUntil 重新判断
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:delete", null, iso(now + 7_200_000L)));
        cache.invalidateAll();

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(Math.abs(cache.getClockOffsetMillis())).isLessThan(5_000L);
    }

    @Test
    void staleCacheIsUsedWhenPermissionCenterIsDown() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:delete", null, null));
        RbacUserPermissionCache cache = newCache(remote);
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);

        cache.invalidateAll();
        remote.failOnUserPermissions = new RbacRemoteException("permission center down");

        // 有旧缓存：用旧缓存 + 时钟偏移继续判定（契约 §7.2 第 5 条）
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
    }

    @Test
    void unavailableWhenDownAndNoCache() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnUserPermissions = new RbacRemoteException("permission center down");
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.UNAVAILABLE);
        assertThatThrownBy(() -> cache.getValidPermissions(USER, SYSTEM))
                .isInstanceOf(RbacRemoteException.class);
    }

    @Test
    void invalidationTriggersReloadAndSeesNewGrant() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        long now = System.currentTimeMillis();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:list", null, null));
        RbacUserPermissionCache cache = newCache(remote);
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);

        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:list", null, null),
                StubRbacOpenApiClient.permission("order:delete", iso(now), iso(now + 600_000L)));
        cache.invalidateAll();

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(remote.userPermissionCalls.get()).isEqualTo(2);
    }

    @Test
    void invalidateUserOnlyAffectsThatUser() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        long now = System.currentTimeMillis();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:list", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        cache.check("user-a", SYSTEM, "order:list");
        cache.check("user-b", SYSTEM, "order:list");
        assertThat(remote.userPermissionCalls.get()).isEqualTo(2);

        cache.invalidateUser("user-a");
        assertThat(cache.check("user-b", SYSTEM, "order:list"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(remote.userPermissionCalls.get()).isEqualTo(2);
        assertThat(cache.check("user-a", SYSTEM, "order:list"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(remote.userPermissionCalls.get()).isEqualTo(3);
    }

    @Test
    void platformAdminWildcardAllowsAnyCode() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("*", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "anything:at:all"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.isPlatformAdmin(USER, SYSTEM)).isTrue();
        assertThat(cache.getValidPermissions(USER, SYSTEM)).containsExactly("*");
    }

    @Test
    void expiredPlatformAdminWildcardIsNotAdmin() {
        long now = System.currentTimeMillis();
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("*", null, iso(now - 1L)));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.isPlatformAdmin(USER, SYSTEM)).isFalse();
    }

    @Test
    void prefixWildcardAllowsCodesUnderPrefixOnly() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:*", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.check(USER, SYSTEM, "order:view:amount"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        // 前缀必须到冒号边界，且不能跨前缀
        assertThat(cache.check(USER, SYSTEM, "order"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        assertThat(cache.check(USER, SYSTEM, "purchase:create"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        // 前缀通配不等价于全量通配
        assertThat(cache.check(USER, SYSTEM, "*"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        assertThat(cache.isPlatformAdmin(USER, SYSTEM)).isFalse();
        assertThat(cache.getValidPermissions(USER, SYSTEM)).containsExactly("order:*");
    }

    @Test
    void prefixWildcardRespectsValidityWindow() {
        long now = System.currentTimeMillis();
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(now),
                StubRbacOpenApiClient.permission("order:*", null, iso(now - 1L)),
                StubRbacOpenApiClient.permission("purchase:*", iso(now + 3_600_000L), null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        assertThat(cache.check(USER, SYSTEM, "purchase:create"))
                .isEqualTo(RbacUserPermissionCache.Decision.DENY);
        assertThat(cache.getValidPermissions(USER, SYSTEM)).isEmpty();
    }

    @Test
    void mixedExactAndPrefixWildcardPermissionsAreKeptAsIs() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(iso(System.currentTimeMillis()),
                StubRbacOpenApiClient.permission("order:list", null, null),
                StubRbacOpenApiClient.permission("order:*", null, null));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:list"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.ALLOW);
        assertThat(cache.getValidPermissions(USER, SYSTEM))
                .containsExactlyInAnyOrder("order:list", "order:*");
    }

    @Test
    void malformedTimestampFailsLoadAndFallsBack() {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.userPermissions = StubRbacOpenApiClient.response(
                iso(System.currentTimeMillis()),
                new UserPermissionEntry("order:delete", null, "not-a-time"));
        RbacUserPermissionCache cache = newCache(remote);

        assertThat(cache.check(USER, SYSTEM, "order:delete"))
                .isEqualTo(RbacUserPermissionCache.Decision.UNAVAILABLE);
    }

    @Test
    void validityWindowBoundaries() {
        // valid_from 闭区间、valid_until 开区间；null 表示不限制
        assertThat(RbacUserPermissionCache.isWithinValidityWindow(null, null, 100L)).isTrue();
        assertThat(RbacUserPermissionCache.isWithinValidityWindow(100L, null, 100L)).isTrue();
        assertThat(RbacUserPermissionCache.isWithinValidityWindow(101L, null, 100L)).isFalse();
        assertThat(RbacUserPermissionCache.isWithinValidityWindow(null, 100L, 99L)).isTrue();
        assertThat(RbacUserPermissionCache.isWithinValidityWindow(null, 100L, 100L)).isFalse();
    }

    @Test
    void parseInstantMillisHandlesIsoAndOffsetFormats() {
        assertThat(RbacUserPermissionCache.parseInstantMillis("1970-01-01T00:00:01Z"))
                .isEqualTo(1000L);
        assertThat(RbacUserPermissionCache.parseInstantMillis("1970-01-01T08:00:01+08:00"))
                .isEqualTo(1000L);
        assertThat(RbacUserPermissionCache.parseInstantMillis(null)).isNull();
        assertThat(RbacUserPermissionCache.parseInstantMillis("bad")).isNull();
    }
}
