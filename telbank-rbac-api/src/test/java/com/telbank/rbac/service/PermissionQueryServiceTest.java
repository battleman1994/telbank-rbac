package com.telbank.rbac.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.OpenApiDtos.PermissionItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限查询时效聚合（需求 §5.3 / 契约 §4.2）：原生 SQL 返回 code + validFrom/validUntil，
 * 服务层转换为 UTC Instant；平台超管返回 {@code *}。
 */
class PermissionQueryServiceTest {

    private final RbacProperties properties = new RbacProperties();

    private PermissionQueryService service(List<Object[]> rows) {
        SysUserRoleRepository repository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("findEffectivePermissionValidity".equals(method.getName())) {
                        return rows;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        return new PermissionQueryService(repository, properties);
    }

    @Test
    void mapsValidityEnvelopeWithUtcInstants() {
        PermissionQueryService service = service(List.of(
                new Object[]{"order:delete", "2026-10-24T00:00:00", "2026-10-31T23:59:59"},
                new Object[]{"order:list", null, null},
                new Object[]{"common:profile", "2026-10-24T00:00:00", null}));

        List<PermissionItem> items = service.effectivePermissions(1L, "13800138000");

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
        PermissionQueryService service = service(List.of(
                new Object[]{"order:delete", "2026-10-24T00:00:00", "2026-10-31T23:59:59"},
                new Object[]{"order:list", null, null}));

        Set<String> codes = service.effectiveCodes(1L, "13800138000");

        assertThat(codes).containsExactlyInAnyOrder("order:delete", "order:list");
    }

    @Test
    void platformAdminReceivesWildcardPermission() {
        properties.setPlatformAdminIds(List.of("platform-admin-001"));
        PermissionQueryService service = service(List.of());
        SysSystem system = new SysSystem();
        system.setId(1L);
        system.setCode("crm");

        List<PermissionItem> items = service.userPermissions(system, "platform-admin-001");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).code()).isEqualTo("*");
        assertThat(items.get(0).validFrom()).isNull();
        assertThat(items.get(0).validUntil()).isNull();
        assertThat(service.isPlatformAdmin("platform-admin-001")).isTrue();
        assertThat(service.isPlatformAdmin("13800138000")).isFalse();
    }

    @Test
    void blankInputsReturnEmptyResult() {
        PermissionQueryService service = service(List.of());
        assertThat(service.effectivePermissions(null, "13800138000")).isEmpty();
        assertThat(service.effectivePermissions(1L, " ")).isEmpty();
        assertThat(service.effectiveCodes(null, null)).isEmpty();
    }

    @Test
    void skipsRowsWithBlankCode() {
        PermissionQueryService service = service(List.of(
                new Object[]{"", "2026-10-24T00:00:00", null},
                new Object[]{null, null, null},
                new Object[]{"order:list", null, null}));
        assertThat(service.effectivePermissions(1L, "u1"))
                .extracting(PermissionItem::code)
                .containsExactly("order:list");
    }
}
