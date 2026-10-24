package com.telbank.rbac.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户可访问系统由有效角色推导（系统隔离模型，排除平台系统）。
 */
class UserAccessServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ERP_ID = 2L;
    private static final Long PLATFORM_ID = 4L;

    @Test
    void normalUserSystemsDerivedFromRoleSystemIdsAndDeduplicated() {
        List<SysSystem> systems = List.of(
                system(CRM_ID, "crm", 1, 0), system(ERP_ID, "erp", 1, 0),
                system(PLATFORM_ID, "platform", 1, 1));
        UserAccessService service = service(systems, List.of(CRM_ID, ERP_ID, ERP_ID), false);

        List<AccessedSystemVO> result = service.accessedSystems("crm-admin-001");

        assertThat(result).extracting(AccessedSystemVO::code).containsExactly("crm", "erp");
        assertThat(result.get(0).isDefault()).isTrue();
        assertThat(result.get(1).isDefault()).isFalse();
    }

    @Test
    void platformSystemIsNeverReturnedForNormalUsers() {
        List<SysSystem> systems = List.of(
                system(CRM_ID, "crm", 1, 0), system(PLATFORM_ID, "platform", 1, 1));
        UserAccessService service = service(systems, List.of(CRM_ID, PLATFORM_ID), false);

        List<SysSystem> accessible = service.accessibleSystems("superadmin-like-user");

        assertThat(accessible).extracting(SysSystem::getCode).containsExactly("crm");
    }

    @Test
    void disabledSystemIsFilteredOut() {
        List<SysSystem> systems = List.of(
                system(CRM_ID, "crm", 1, 0), system(ERP_ID, "erp", 0, 0));
        UserAccessService service = service(systems, List.of(CRM_ID, ERP_ID), false);

        assertThat(service.accessibleSystems("crm-admin-001"))
                .extracting(SysSystem::getCode)
                .containsExactly("crm");
    }

    @Test
    void platformAdminGetsAllEnabledBusinessSystems() {
        List<SysSystem> systems = List.of(
                system(CRM_ID, "crm", 1, 0), system(ERP_ID, "erp", 1, 0),
                system(PLATFORM_ID, "platform", 1, 1));
        UserAccessService service = service(systems, List.of(), true);

        List<AccessedSystemVO> result = service.accessedSystems("superadmin");

        assertThat(result).extracting(AccessedSystemVO::code).containsExactly("crm", "erp");
        assertThat(result.get(0).isDefault()).isTrue();
    }

    @Test
    void userWithoutRolesHasNoSystems() {
        UserAccessService service = service(List.of(system(CRM_ID, "crm", 1, 0)), List.of(), false);
        assertThat(service.accessibleSystems("13600136000")).isEmpty();
        assertThat(service.accessedSystems("13600136000")).isEmpty();
    }

    @Test
    void accessibleSystemQueryAppliesRoleValidityFilters() throws NoSuchMethodException {
        Method method = SysUserRoleRepository.class.getMethod("findAccessibleSystemIds", String.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.value())
                .contains("DISTINCT ur.system_id")
                .contains("ur.status = 1")
                .contains("r.status = 1")
                .contains("ur.valid_from IS NULL OR ur.valid_from <= NOW()")
                .contains("ur.valid_until IS NULL OR ur.valid_until > NOW()");
    }

    @Test
    void enabledBusinessSystemsQueryExcludesPlatformSystems() throws NoSuchMethodException {
        Method method = SysSystemRepository.class.getMethod("findEnabledBusinessSystems", Integer.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.value())
                .contains("s.isPlatform", "s.status = :status");
    }

    private UserAccessService service(List<SysSystem> systems, List<Long> accessibleIds, boolean platformAdmin) {
        SysSystemRepository systemRepository = TestProxies.proxy(SysSystemRepository.class,
                (proxy, method, args) -> {
                    if ("findAllById".equals(method.getName())) {
                        List<Long> ids = new ArrayList<>();
                        for (Object id : (Iterable<?>) args[0]) {
                            ids.add((Long) id);
                        }
                        return systems.stream().filter(system -> ids.contains(system.getId())).toList();
                    }
                    if ("findEnabledBusinessSystems".equals(method.getName())) {
                        return systems.stream()
                                .filter(system -> system.getStatus() == 1
                                        && (system.getIsPlatform() == null || system.getIsPlatform() != 1))
                                .toList();
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return systems.stream().filter(system -> system.getIsPlatform() == 1).findFirst();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleRepository userRoleRepository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("findAccessibleSystemIds".equals(method.getName())) {
                        return accessibleIds;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        return platformAdmin ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        return new UserAccessService(systemRepository, userRoleRepository, platformAdminService);
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
