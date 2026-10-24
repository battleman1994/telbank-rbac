package com.telbank.rbac.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.OpenApiDtos.PermissionItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限查询时效聚合（需求 §5.3 / 契约 §4.2）：原生 SQL 返回 code + validFrom/validUntil，
 * 服务层转换为 UTC Instant；平台超管由 DB（platform:admin）判定并返回 {@code *}。
 */
class PermissionQueryServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long PLATFORM_ID = 4L;

    private PermissionQueryService service(List<Object[]> rows, boolean superAdmin) {
        SysUserRoleRepository repository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("findEffectivePermissionValidity".equals(method.getName())) {
                        return rows;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        return superAdmin && ((Long) args[0]) == PLATFORM_ID ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysSystemRepository systemRepository = TestProxies.proxy(SysSystemRepository.class,
                (proxy, method, args) -> {
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        SysSystem platform = new SysSystem();
                        platform.setId(PLATFORM_ID);
                        platform.setCode("platform");
                        platform.setStatus(1);
                        platform.setIsPlatform(1);
                        return Optional.of(platform);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, repository);
        return new PermissionQueryService(repository, platformAdminService);
    }

    @Test
    void mapsValidityEnvelopeWithUtcInstants() {
        PermissionQueryService service = service(List.<Object[]>of(
                new Object[]{"order:delete", "2026-10-24T00:00:00", "2026-10-31T23:59:59"},
                new Object[]{"order:list", null, null},
                new Object[]{"common:profile", "2026-10-24T00:00:00", null}), false);

        List<PermissionItem> items = service.effectivePermissions(CRM_ID, "13800138000");

        assertThat(items).hasSize(3);
        PermissionItem delete = items.get(0);
        assertThat(delete.code()).isEqualTo("order:delete");
        assertThat(delete.validFrom()).isEqualTo(Instant.parse("2026-10-24T00:00:00Z"));
        assertThat(delete.validUntil()).isEqualTo(Instant.parse("2026-10-31T23:59:59Z"));

        PermissionItem list = items.get(1);
        assertThat(list.code()).isEqualTo("order:list");
        assertThat(list.validFrom()).isNull();
        assertThat(list.validUntil()).isNull();

        PermissionItem profile = items.get(2);
        assertThat(profile.code()).isEqualTo("common:profile");
        assertThat(profile.validUntil()).isNull();
    }

    @Test
    void effectiveCodesReturnsSetOfValidPermissionCodes() {
        PermissionQueryService service = service(List.<Object[]>of(
                new Object[]{"role:manage", null, null},
                new Object[]{"page:manage", "2026-10-24T00:00:00", null}), false);

        Set<String> codes = service.effectiveCodes(CRM_ID, "crm-admin-001");

        assertThat(codes).containsExactlyInAnyOrder("role:manage", "page:manage");
    }

    @Test
    void platformAdminReceivesWildcardPermissionsFromDatabase() {
        PermissionQueryService service = service(List.of(), true);
        SysSystem crm = new SysSystem();
        crm.setId(CRM_ID);
        crm.setCode("crm");

        List<PermissionItem> items = service.userPermissions(crm, "superadmin");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).code()).isEqualTo("*");
        assertThat(items.get(0).validFrom()).isNull();
        assertThat(items.get(0).validUntil()).isNull();
    }

    @Test
    void normalUserReceivesEffectivePermissions() {
        PermissionQueryService service = service(List.<Object[]>of(
                new Object[]{"role:manage", null, null}), false);
        SysSystem crm = new SysSystem();
        crm.setId(CRM_ID);
        crm.setCode("crm");

        assertThat(service.userPermissions(crm, "crm-admin-001"))
                .extracting(PermissionItem::code)
                .containsExactly("role:manage");
    }

    @Test
    void blankInputsReturnEmptyResult() {
        PermissionQueryService service = service(List.of(), false);
        assertThat(service.effectivePermissions(null, "13800138000")).isEmpty();
        assertThat(service.effectivePermissions(1L, " ")).isEmpty();
        assertThat(service.effectiveCodes(null, null)).isEmpty();
    }

    @Test
    void skipsRowsWithBlankCode() {
        PermissionQueryService service = service(List.<Object[]>of(
                new Object[]{"", "2026-10-24T00:00:00", null},
                new Object[]{null, null, null},
                new Object[]{"order:list", null, null}), false);
        assertThat(service.effectivePermissions(1L, "u1"))
                .extracting(PermissionItem::code)
                .containsExactly("order:list");
    }
}
