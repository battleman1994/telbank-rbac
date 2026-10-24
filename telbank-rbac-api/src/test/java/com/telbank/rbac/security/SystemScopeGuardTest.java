package com.telbank.rbac.security;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.service.PlatformAdminService;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 严格系统隔离：任何用户（含平台超管）systemId/资源必须与当前管理上下文一致，否则 403。
 */
class SystemScopeGuardTest {

    private SystemScopeGuard guard;

    @BeforeEach
    void setUp() {
        SysSystem crm = system(1L, "crm", 1, 0);
        SysSystem erp = system(2L, "erp", 1, 0);
        SysSystem platform = system(4L, "platform", 1, 1);
        Map<String, SysSystem> systems = Map.of("crm", crm, "erp", erp, "platform", platform);

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return Optional.ofNullable(systems.get((String) args[0]));
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.of(platform);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    if ("countEffectiveRoles".equals(method.getName())) {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        return systemId == 1L && "crm-admin-001".equals(userId) ? 1L : 0L;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        return ((Long) args[0]) == 4L && "superadmin".equals(args[1]) ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        guard = new SystemScopeGuard(contextService);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void normalUserCanAccessOwnSystemOnly() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThatCode(() -> guard.requireAccess(1L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireAccess(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireAccess(2L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("cross-system");
                });
    }

    @Test
    void normalUserQuerySystemIdIsScopedToContext() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThat(guard.resolveQuerySystemId(null)).isEqualTo(1L);
        assertThat(guard.resolveQuerySystemId(1L)).isEqualTo(1L);
        assertThatThrownBy(() -> guard.resolveQuerySystemId(2L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(403));
    }

    @Test
    void platformAdminCannotCrossSystemsEither() {
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("crm");

        // 严格隔离：超管也必须在当前上下文系统内，无跨系统旁路
        assertThatThrownBy(() -> guard.requireAccess(2L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("cross-system");
                });
        assertThatThrownBy(() -> guard.resolveQuerySystemId(2L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(403));
        assertThatCode(() -> guard.requireAccess(1L)).doesNotThrowAnyException();
        assertThat(guard.resolveQuerySystemId(null)).isEqualTo(1L);
        assertThat(guard.resolveQuerySystemId(1L)).isEqualTo(1L);
    }

    @Test
    void normalUserWithoutSystemHeaderIsForbidden() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode(null);

        assertThatThrownBy(() -> guard.requireAccess(1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(403));
    }

    private static SysSystem system(Long id, String code, int status, int isPlatform) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(code);
        system.setRegion("region_a");
        system.setStatus(status);
        system.setSort(id.intValue());
        system.setIsPlatform(isPlatform);
        return system;
    }
}
