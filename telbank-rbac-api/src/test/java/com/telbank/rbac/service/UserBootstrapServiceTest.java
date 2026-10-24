package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.row.PermissionValidityRow;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端 bootstrap（系统隔离模型）：currentSystemCode 来自合法 X-System-Code 否则第一个可访问业务系统；
 * 权限码为当前系统内有效短权限码；超管 ["*"] 且可访问全部业务系统（排除 platform）。
 */
class UserBootstrapServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ERP_ID = 2L;
    private static final Long PLATFORM_ID = 4L;
    private static final String CRM_ADMIN = "crm-admin-001";
    private static final String SUPER_ADMIN = "superadmin";

    private UserBootstrapService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = system(CRM_ID, "crm", 1, 0);
        SysSystem erp = system(ERP_ID, "erp", 1, 0);
        SysSystem platform = system(PLATFORM_ID, "platform", 1, 1);
        List<SysSystem> systems = List.of(crm, erp, platform);
        Map<String, SysSystem> byCode = Map.of("crm", crm, "erp", erp, "platform", platform);

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByCode" -> Optional.ofNullable(byCode.get((String) args[0]));
                    case "findById" -> systems.stream()
                            .filter(system -> system.getId().equals(args[0])).findFirst();
                    case "findFirstByIsPlatformOrderByIdAsc" -> Optional.of(platform);
                    case "findAllById" -> {
                        List<Long> ids = new ArrayList<>();
                        for (Object id : (Iterable<?>) args[0]) {
                            ids.add((Long) id);
                        }
                        yield systems.stream().filter(system -> ids.contains(system.getId())).toList();
                    }
                    case "findEnabledBusinessSystems" -> systems.stream()
                            .filter(system -> system.getStatus() == 1
                                    && (system.getIsPlatform() == null || system.getIsPlatform() != 1))
                            .toList();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAccessibleSystemIds" ->
                            CRM_ADMIN.equals(args[0]) ? List.of(CRM_ID) : List.of();
                    case "countEffectiveRoles" -> {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        yield systemId == CRM_ID && CRM_ADMIN.equals(userId) ? 1L : 0L;
                    }
                    case "countEffectivePermission" -> {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        yield systemId == PLATFORM_ID && SUPER_ADMIN.equals(userId) ? 1L : 0L;
                    }
                    case "findEffectivePermissionValidity" -> {
                        Long systemId = (Long) args[0];
                        yield systemId == CRM_ID
                                ? List.of(
                                        row("page:manage", "2026-10-24T00:00:00", null),
                                        row("role:manage", null, null))
                                : List.of();
                    }
                    default -> TestProxies.defaultValue(method.getReturnType());
                });

        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        PermissionQueryService permissionQueryService =
                new PermissionQueryService(userRoleRepository, platformAdminService);
        UserAccessService userAccessService =
                new UserAccessService(systemRepository, userRoleRepository, platformAdminService);
        service = new UserBootstrapService(userAccessService, systemRepository, permissionQueryService);
    }

    @Test
    void currentSystemFromRequestHeaderWhenAccessible() {
        BootstrapVO bootstrap = service.bootstrap(CRM_ADMIN, false, "crm");

        assertThat(bootstrap.externalUserId()).isEqualTo(CRM_ADMIN);
        assertThat(bootstrap.platformAdmin()).isFalse();
        assertThat(bootstrap.currentSystemCode()).isEqualTo("crm");
        assertThat(bootstrap.permissions()).containsExactly("page:manage", "role:manage");
        assertThat(bootstrap.systems()).extracting(AccessedSystemVO::code).containsExactly("crm");
        assertThat(bootstrap.systems().get(0).isDefault()).isTrue();
    }

    @Test
    void currentSystemFallsBackToFirstAccessibleSystem() {
        BootstrapVO bootstrap = service.bootstrap(CRM_ADMIN, false, null);
        assertThat(bootstrap.currentSystemCode()).isEqualTo("crm");
    }

    @Test
    void inaccessibleRequestedSystemFallsBackToFirstAccessibleSystem() {
        BootstrapVO bootstrap = service.bootstrap(CRM_ADMIN, false, "erp");
        assertThat(bootstrap.currentSystemCode()).isEqualTo("crm");
    }

    @Test
    void platformAdminGetsAllBusinessSystemsAndWildcardPermissions() {
        BootstrapVO bootstrap = service.bootstrap(SUPER_ADMIN, true, "erp");

        assertThat(bootstrap.platformAdmin()).isTrue();
        assertThat(bootstrap.currentSystemCode()).isEqualTo("erp");
        assertThat(bootstrap.permissions()).containsExactly("*");
        assertThat(bootstrap.systems()).extracting(AccessedSystemVO::code)
                .containsExactly("crm", "erp");
        assertThat(bootstrap.systems()).noneMatch(system -> "platform".equals(system.code()));
        assertThat(bootstrap.systems().get(0).isDefault()).isTrue();
    }

    @Test
    void platformAdminWithoutHeaderFallsBackToFirstBusinessSystem() {
        BootstrapVO bootstrap = service.bootstrap(SUPER_ADMIN, true, null);
        assertThat(bootstrap.currentSystemCode()).isEqualTo("crm");
    }

    @Test
    void userWithoutRolesGetsEmptyBootstrap() {
        BootstrapVO bootstrap = service.bootstrap("13600136000", false, null);

        assertThat(bootstrap.currentSystemCode()).isNull();
        assertThat(bootstrap.permissions()).isEmpty();
        assertThat(bootstrap.systems()).isEmpty();
    }

    @Test
    void bootstrapSerializesWithContractFieldNames() {
        BootstrapVO bootstrap = new BootstrapVO(CRM_ADMIN, false, "crm",
                List.of("role:manage", "page:manage"),
                List.of(new AccessedSystemVO(CRM_ID, "crm", "CRM系统", "region_a", true)));

        String json = JsonMapper.builder().build().writeValueAsString(bootstrap);

        assertThat(json)
                .contains("\"externalUserId\":\"" + CRM_ADMIN + "\"")
                .contains("\"platformAdmin\":false")
                .contains("\"currentSystemCode\":\"crm\"")
                .contains("\"permissions\":[\"role:manage\",\"page:manage\"]")
                .contains("\"systems\":[{\"id\":1,\"code\":\"crm\",\"name\":\"CRM系统\","
                        + "\"region\":\"region_a\",\"isDefault\":true}]");
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
