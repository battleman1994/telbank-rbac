package com.telbank.rbac.security;

import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理上下文：/api/** 要求 X-System-Code 且必须是业务系统；
 * 超管可进入任意业务系统，普通用户必须有该系统的有效角色。
 */
class ManagementContextServiceTest {

    private ManagementContextService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = system(1L, "crm", 1, 0);
        SysSystem erp = system(2L, "erp", 1, 0);
        SysSystem disabled = system(3L, "legacy", 0, 0);
        SysSystem platform = system(4L, "platform", 1, 1);
        Map<String, SysSystem> systems = Map.of(
                "crm", crm, "erp", erp, "legacy", disabled, "platform", platform);

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
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        return systemId == 4L && "superadmin".equals(userId) ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        service = new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void missingHeaderIsForbidden() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode(null);

        assertForbidden(() -> service.requireContext(), "X-System-Code");
    }

    @Test
    void unknownSystemIsForbidden() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("unknown");

        assertForbidden(() -> service.requireContext(), "invalid or disabled system context");
    }

    @Test
    void platformSystemIsNotAllowedAsManagementContext() {
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("platform");

        assertForbidden(() -> service.requireContext(), "invalid or disabled system context");
    }

    @Test
    void disabledSystemIsForbidden() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("legacy");

        assertForbidden(() -> service.requireContext(), "invalid or disabled system context");
    }

    @Test
    void normalUserWithoutEffectiveRoleIsForbidden() {
        SecurityTestSupport.authenticate("13600136000");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertForbidden(() -> service.requireContext(), "no access to system");
    }

    @Test
    void normalUserWithEffectiveRoleGetsContext() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        ManagementContext context = service.requireContext();

        assertThat(context.systemId()).isEqualTo(1L);
        assertThat(context.systemCode()).isEqualTo("crm");
        assertThat(service.currentContext()).contains(context);
    }

    @Test
    void platformAdminCanEnterAnyBusinessSystem() {
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("erp");

        ManagementContext context = service.requireContext();

        assertThat(context.systemCode()).isEqualTo("erp");
        assertThat(context.systemId()).isEqualTo(2L);
    }

    @Test
    void softResolveReturnsEmptyForInvalidContext() {
        SecurityTestSupport.authenticate("13600136000");
        SecurityTestSupport.requestWithSystemCode("crm");
        assertThat(service.resolveSoft()).isEmpty();
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                        String messagePart) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains(messagePart);
                });
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
