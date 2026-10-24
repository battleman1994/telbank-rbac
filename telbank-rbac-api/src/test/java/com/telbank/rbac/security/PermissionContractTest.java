package com.telbank.rbac.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.row.PermissionValidityRow;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.service.PermissionQueryService;
import com.telbank.rbac.service.PlatformAdminService;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.admin.ApprovalAuditController;
import com.telbank.rbac.web.admin.PageActionController;
import com.telbank.rbac.web.admin.PermissionController;
import com.telbank.rbac.web.admin.RoleController;
import com.telbank.rbac.web.admin.RolePermissionController;
import com.telbank.rbac.web.admin.SdkEndpointController;
import com.telbank.rbac.web.admin.SystemController;
import com.telbank.rbac.web.admin.UserRoleController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端权限码契约（系统隔离模型）：各 Controller 使用系统内短权限码，系统接入仅超管；
 * {@code @perm} 在当前管理上下文系统内校验权限码。
 */
class PermissionContractTest {

    private PermBean permBean;

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
                    switch (method.getName()) {
                        case "countEffectiveRoles": {
                            Long systemId = (Long) args[0];
                            String userId = (String) args[1];
                            return systemId == 1L && "crm-admin-001".equals(userId) ? 1L : 0L;
                        }
                        case "countEffectivePermission": {
                            Long systemId = (Long) args[0];
                            String userId = (String) args[1];
                            return systemId == 4L && "superadmin".equals(userId) ? 1L : 0L;
                        }
                        case "findEffectivePermissionValidity": {
                            Long systemId = (Long) args[0];
                            if (systemId == 1L) {
                                return List.of(
                                        row("role:manage", null, null),
                                        row("page:manage", "2026-10-24T00:00:00", null));
                            }
                            return List.of();
                        }
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
    void controllersUseSystemScopedShortPermissionCodes() {
        assertPreAuthorize(RoleController.class, "@perm.has('role:manage')");
        assertPreAuthorize(RolePermissionController.class, "@perm.has('role:manage')");
        assertPreAuthorize(PermissionController.class, "@perm.has('permission:manage')");
        assertPreAuthorize(SdkEndpointController.class, "@perm.has('resource:manage')");
        assertPreAuthorize(PageActionController.class, "@perm.has('page:manage')");
        assertPreAuthorize(UserRoleController.class, "@perm.has('user:manage')");
        assertPreAuthorize(ApprovalAuditController.class, "@perm.has('audit:view')");
        assertPreAuthorize(SystemController.class, "@perm.isPlatformAdmin()");
    }

    @Test
    void systemOnboardingHasNoDeleteEndpoint() {
        assertThat(SystemController.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class));
    }

    @Test
    void userSystemControllerIsRemoved() {
        assertThatThrownBy(() -> Class.forName("com.telbank.rbac.web.admin.UserSystemController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void permBeanChecksShortCodesInCurrentSystemContext() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        assertThat(permBean.isPlatformAdmin()).isFalse();
        assertThat(permBean.has("role:manage")).isTrue();
        assertThat(permBean.has("page:manage")).isTrue();
        assertThat(permBean.has("permission:manage")).isFalse();
        assertThat(permBean.has("role:manage", "page:manage")).isTrue();
        assertThat(permBean.has("role:manage", "permission:manage")).isFalse();
        assertThat(permBean.hasAny("permission:manage", "role:manage")).isTrue();
    }

    @Test
    void permBeanWithoutSystemContextIsDenied() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode(null);

        assertThat(permBean.has("role:manage")).isFalse();
    }

    @Test
    void platformAdminBypassesPermissionCodes() {
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("erp");

        assertThat(permBean.isPlatformAdmin()).isTrue();
        assertThat(permBean.has("role:manage")).isTrue();
        assertThat(permBean.has("anything:not-defined")).isTrue();
        assertThat(permBean.hasAny("x:y")).isTrue();
    }

    private static void assertPreAuthorize(Class<?> controllerType, String expectedExpression) {
        PreAuthorize preAuthorize = controllerType.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).as(controllerType.getSimpleName() + " @PreAuthorize").isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expectedExpression);
    }

    private static PermissionValidityRow row(String code, String validFrom, String validUntil) {
        PermissionValidityRow row = new PermissionValidityRow();
        row.setCode(code);
        row.setValidFrom(validFrom);
        row.setValidUntil(validUntil);
        return row;
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
