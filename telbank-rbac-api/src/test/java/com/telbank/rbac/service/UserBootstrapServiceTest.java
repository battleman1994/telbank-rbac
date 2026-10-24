package com.telbank.rbac.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.domain.repository.SysUserSystemRepository;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端 bootstrap（权限中心自身接入 RBAC）：
 * 管理系统中经时效过滤的权限码、平台超管通配、管理系统缺失降级、systems 复用 me/systems。
 */
class UserBootstrapServiceTest {

    private static final String MANAGEMENT_SYSTEM_CODE = "rbac";
    private static final Long MANAGEMENT_SYSTEM_ID = 3L;
    private static final Long CRM_SYSTEM_ID = 1L;
    private static final String ADMIN_USER_ID = "13800138000";

    private final RbacProperties properties = new RbacProperties();
    private final AtomicReference<Long> requestedSystemId = new AtomicReference<>();
    private final AtomicReference<String> requestedExternalUserId = new AtomicReference<>();
    private final AtomicReference<String> requestedSystemCode = new AtomicReference<>();

    private SysSystem managementSystem;
    private SysSystem crmSystem;
    private List<Object[]> permissionRows = List.of();
    private List<SysUserSystem> userSystemRows = List.of();

    private UserBootstrapService service;

    @BeforeEach
    void setUp() {
        properties.setPlatformAdminIds(List.of("platform-admin-001"));
        managementSystem = system(MANAGEMENT_SYSTEM_ID, MANAGEMENT_SYSTEM_CODE, "权限中心", 1);
        crmSystem = system(CRM_SYSTEM_ID, "crm", "CRM系统", 1);

        SysSystemRepository systemRepository = TestProxies.proxy(SysSystemRepository.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByCode" -> {
                        requestedSystemCode.set((String) args[0]);
                        if (managementSystem != null && managementSystem.getCode().equals(args[0])) {
                            yield Optional.of(managementSystem);
                        }
                        yield Optional.empty();
                    }
                    case "findAllByStatusOrderBySortAscIdAsc" -> enabledSystems();
                    case "findAllById" -> findAllById(enabledSystems(), args[0]);
                    default -> TestProxies.defaultValue(method.getReturnType());
                });

        SysUserSystemRepository userSystemRepository = TestProxies.proxy(SysUserSystemRepository.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByExternalUserIdOrderByIdAsc" -> userSystemRows;
                    case "findByExternalUserIdAndStatusOrderByIsDefaultDescIdAsc" -> userSystemRows;
                    default -> TestProxies.defaultValue(method.getReturnType());
                });

        SysUserRoleRepository userRoleRepository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("findEffectivePermissionValidity".equals(method.getName())) {
                        requestedSystemId.set((Long) args[0]);
                        requestedExternalUserId.set((String) args[1]);
                        return permissionRows;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        PermissionQueryService permissionQueryService =
                new PermissionQueryService(userRoleRepository, properties);
        UserSystemService userSystemService = new UserSystemService(
                userSystemRepository, systemRepository, permissionQueryService);
        service = new UserBootstrapService(properties, systemRepository, permissionQueryService,
                userSystemService);
    }

    @Test
    void platformAdminGetsWildcardPermissionAndAllEnabledSystems() {
        BootstrapVO bootstrap = service.bootstrap("platform-admin-001", true);

        assertThat(bootstrap.externalUserId()).isEqualTo("platform-admin-001");
        assertThat(bootstrap.platformAdmin()).isTrue();
        assertThat(bootstrap.managementSystemCode()).isEqualTo(MANAGEMENT_SYSTEM_CODE);
        assertThat(bootstrap.permissions()).containsExactly("*");
        assertThat(bootstrap.systems()).hasSize(2);
        assertThat(bootstrap.systems().get(0).code()).isEqualTo("crm");
        assertThat(bootstrap.systems().get(0).isDefault()).isTrue();
        assertThat(bootstrap.systems().get(1).code()).isEqualTo(MANAGEMENT_SYSTEM_CODE);
        // 平台超管短路：不查管理系统的权限码
        assertThat(requestedSystemId.get()).isNull();
    }

    @Test
    void normalAdminGetsFiveRbacPermissionsFromManagementSystem() {
        permissionRows = List.<Object[]>of(
                row("rbac:audit:view"), row("rbac:page:manage"), row("rbac:permission:manage"),
                row("rbac:role:manage"), row("rbac:system:manage"));
        userSystemRows = List.of(
                userSystem(1L, CRM_SYSTEM_ID, 1), userSystem(2L, MANAGEMENT_SYSTEM_ID, 0));

        BootstrapVO bootstrap = service.bootstrap(ADMIN_USER_ID, false);

        assertThat(bootstrap.platformAdmin()).isFalse();
        assertThat(bootstrap.permissions()).containsExactly(
                "rbac:audit:view", "rbac:page:manage", "rbac:permission:manage",
                "rbac:role:manage", "rbac:system:manage");
        assertThat(requestedSystemCode.get()).isEqualTo(MANAGEMENT_SYSTEM_CODE);
        assertThat(requestedSystemId.get()).isEqualTo(MANAGEMENT_SYSTEM_ID);
        assertThat(requestedExternalUserId.get()).isEqualTo(ADMIN_USER_ID);
        assertThat(bootstrap.systems()).hasSize(2);
        assertThat(bootstrap.systems().get(0).code()).isEqualTo("crm");
        assertThat(bootstrap.systems().get(0).isDefault()).isTrue();
        assertThat(bootstrap.systems().get(1).code()).isEqualTo(MANAGEMENT_SYSTEM_CODE);
    }

    @Test
    void userWithoutManagementPermissionGetsEmptyPermissions() {
        permissionRows = List.of();
        userSystemRows = List.of(userSystem(1L, CRM_SYSTEM_ID, 1));

        BootstrapVO bootstrap = service.bootstrap("13600136000", false);

        assertThat(bootstrap.permissions()).isEmpty();
        assertThat(bootstrap.systems()).hasSize(1);
        assertThat(bootstrap.systems().get(0).code()).isEqualTo("crm");
    }

    @Test
    void missingManagementSystemDegradesToEmptyPermissionsWithoutError() {
        managementSystem = null;
        userSystemRows = List.of(userSystem(1L, CRM_SYSTEM_ID, 1));

        BootstrapVO bootstrap = service.bootstrap(ADMIN_USER_ID, false);

        assertThat(bootstrap.managementSystemCode()).isEqualTo(MANAGEMENT_SYSTEM_CODE);
        assertThat(bootstrap.permissions()).isEmpty();
        assertThat(bootstrap.systems()).hasSize(1);
        // 管理系统缺失时不应查询权限码
        assertThat(requestedSystemId.get()).isNull();
    }

    @Test
    void disabledManagementSystemDegradesToEmptyPermissions() {
        managementSystem = system(MANAGEMENT_SYSTEM_ID, MANAGEMENT_SYSTEM_CODE, "权限中心", 0);
        permissionRows = List.<Object[]>of(row("rbac:system:manage"));

        BootstrapVO bootstrap = service.bootstrap(ADMIN_USER_ID, false);

        assertThat(bootstrap.permissions()).isEmpty();
        assertThat(requestedSystemId.get()).isNull();
    }

    @Test
    void customManagementSystemCodeIsUsedAndReturned() {
        properties.setManagementSystemCode("rbac2");
        managementSystem = system(99L, "rbac2", "权限中心v2", 1);
        permissionRows = List.<Object[]>of(row("rbac:system:manage"), row("rbac:role:manage"));

        BootstrapVO bootstrap = service.bootstrap(ADMIN_USER_ID, false);

        assertThat(bootstrap.managementSystemCode()).isEqualTo("rbac2");
        assertThat(bootstrap.permissions()).containsExactly("rbac:system:manage", "rbac:role:manage");
        assertThat(requestedSystemId.get()).isEqualTo(99L);
    }

    @Test
    void bootstrapVoSerializesWithContractFieldNames() {
        BootstrapVO bootstrap = new BootstrapVO(ADMIN_USER_ID, false, MANAGEMENT_SYSTEM_CODE,
                List.of("rbac:system:manage", "rbac:role:manage"),
                List.of(new AccessedSystemVO(1L, "crm", "CRM系统", "region_a", true)));

        String json = JsonMapper.builder().build().writeValueAsString(bootstrap);

        assertThat(json)
                .contains("\"externalUserId\":\"" + ADMIN_USER_ID + "\"")
                .contains("\"platformAdmin\":false")
                .contains("\"managementSystemCode\":\"" + MANAGEMENT_SYSTEM_CODE + "\"")
                .contains("\"permissions\":[\"rbac:system:manage\",\"rbac:role:manage\"]")
                .contains("\"systems\":[{\"id\":1,\"code\":\"crm\",\"name\":\"CRM系统\","
                        + "\"region\":\"region_a\",\"isDefault\":true}]");
    }

    // ------------------------------------------------------------------

    private List<SysSystem> enabledSystems() {
        if (managementSystem == null) {
            return List.of(crmSystem);
        }
        return List.of(crmSystem, managementSystem);
    }

    private static List<SysSystem> findAllById(List<SysSystem> systems, Object idsArgument) {
        Collection<?> ids = (Collection<?>) idsArgument;
        return systems.stream().filter(system -> ids.contains(system.getId())).toList();
    }

    private static SysSystem system(Long id, String code, String name, int status) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(name);
        system.setRegion("region_a");
        system.setStatus(status);
        system.setSort(id.intValue());
        return system;
    }

    private static SysUserSystem userSystem(Long id, Long systemId, int isDefault) {
        SysUserSystem relation = new SysUserSystem();
        relation.setId(id);
        relation.setSystemId(systemId);
        relation.setExternalUserId(ADMIN_USER_ID);
        relation.setStatus(1);
        relation.setIsDefault(isDefault);
        return relation;
    }

    private static Object[] row(String code) {
        return new Object[]{code, null, null};
    }
}
