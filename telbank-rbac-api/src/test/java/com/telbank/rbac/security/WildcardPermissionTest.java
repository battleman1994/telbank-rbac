package com.telbank.rbac.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.service.PermissionQueryService;
import com.telbank.rbac.service.PlatformAdminService;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 方案通配权限接入 {@code @perm}：admin 角色一条 {@code *} 即可通过所有管理码断言；
 * {@code 前缀:*} 按命名空间匹配；平台超管仍短路放行。
 */
class WildcardPermissionTest {

    private static final Long CRM_ID = 1L;
    private static final Long PLATFORM_ID = 4L;

    private PermBean permBean;
    private List<Object[]> permissionRows = List.<Object[]>of(new Object[]{"*", null, null});

    @BeforeEach
    void setUp() {
        SysSystem crm = system(CRM_ID, "crm", 1, 0);
        SysSystem platform = system(PLATFORM_ID, "platform", 1, 1);
        Map<String, SysSystem> systems = Map.of("crm", crm, "platform", platform);

        SysSystemRepository systemRepository = TestProxies.proxy(SysSystemRepository.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return Optional.ofNullable(systems.get((String) args[0]));
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.of(platform);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleRepository userRoleRepository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "countEffectiveRoles": {
                            Long systemId = (Long) args[0];
                            String userId = (String) args[1];
                            return systemId == CRM_ID && "crm-admin-001".equals(userId) ? 1L : 0L;
                        }
                        case "countEffectivePermission": {
                            Long systemId = (Long) args[0];
                            String userId = (String) args[1];
                            return systemId == PLATFORM_ID && "superadmin".equals(userId) ? 1L : 0L;
                        }
                        case "findEffectivePermissionValidity":
                            return permissionRows;
                        default:
                            return TestProxies.defaultValue(method.getReturnType());
                    }
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        PermissionQueryService permissionQueryService =
                new PermissionQueryService(userRoleRepository, platformAdminService);
        permBean = new PermBean(currentUserProvider, contextService, permissionQueryService);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void adminRoleWithSingleWildcardPassesAllManagementCodeAssertions() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThat(permBean.has("role:manage")).isTrue();
        assertThat(permBean.has("permission:manage")).isTrue();
        assertThat(permBean.has("resource:manage")).isTrue();
        assertThat(permBean.has("page:manage")).isTrue();
        assertThat(permBean.has("user:manage")).isTrue();
        assertThat(permBean.has("audit:view")).isTrue();
        assertThat(permBean.has("role:manage", "permission:manage", "page:manage")).isTrue();
        assertThat(permBean.hasAny("any:code")).isTrue();
        assertThat(permBean.isPlatformAdmin()).isFalse();
    }

    @Test
    void prefixWildcardOnlyPassesMatchingNamespace() {
        permissionRows = List.<Object[]>of(
                new Object[]{"order:*", null, null},
                new Object[]{"role:manage", null, null});
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThat(permBean.has("order:delete")).isTrue();
        assertThat(permBean.has("order:list")).isTrue();
        assertThat(permBean.has("role:manage")).isTrue();
        assertThat(permBean.has("page:manage")).isFalse();
        assertThat(permBean.has("order:delete", "page:manage")).isFalse();
        assertThat(permBean.hasAny("page:manage", "order:delete")).isTrue();
    }

    @Test
    void platformAdminStillShortCircuitsPermissionChecks() {
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThat(permBean.isPlatformAdmin()).isTrue();
        assertThat(permBean.has("role:manage")).isTrue();
        assertThat(permBean.has("anything:not-defined")).isTrue();
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
