package com.telbank.rbac.service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台超管判定（数据驱动）：平台系统（is_platform=1）内有效 {@code platform:admin} 权限即超管。
 */
class PlatformAdminServiceTest {

    private final AtomicReference<String> requestedCode = new AtomicReference<>();

    private PlatformAdminService service(SysSystem platformSystem, List<String> adminUserIds) {
        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.ofNullable(platformSystem);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    if ("countEffectivePermission".equals(method.getName())) {
                        requestedCode.set((String) args[2]);
                        return adminUserIds.contains((String) args[1]) ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        return new PlatformAdminService(systemRepository, userRoleRepository);
    }

    @Test
    void superAdminDetectedByPlatformAdminPermission() {
        PlatformAdminService service = service(system(4L, "platform", 1, 1),
                List.of("superadmin"));
        assertThat(service.isPlatformAdmin("superadmin")).isTrue();
        assertThat(service.isPlatformAdmin("crm-admin-001")).isFalse();
        assertThat(service.isPlatformAdmin("13800138000")).isFalse();
        // A 方案：平台超管判定查询的是平台系统内的通配权限 *
        assertThat(requestedCode.get()).isEqualTo("*");
    }

    @Test
    void noPlatformSystemMeansNoPlatformAdmin() {
        PlatformAdminService service = service(null, List.of("superadmin"));
        assertThat(service.isPlatformAdmin("superadmin")).isFalse();
        assertThat(service.platformSystem()).isEmpty();
    }

    @Test
    void blankUserIsNotPlatformAdmin() {
        PlatformAdminService service = service(system(4L, "platform", 1, 1), List.of("superadmin"));
        assertThat(service.isPlatformAdmin(null)).isFalse();
        assertThat(service.isPlatformAdmin(" ")).isFalse();
    }

    @Test
    void platformAdminQueryAppliesValidityAndStatusFilters() throws NoSuchMethodException {
        Method method = SysUserRoleMapper.class.getMethod("countEffectivePermission",
                Long.class, String.class, String.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        assertThat(String.join(" ", select.value()))
                .contains("ur.status = 1")
                .contains("r.status = 1")
                .contains("rp.status = 1")
                .contains("p.status = 1")
                .contains("ur.valid_from IS NULL OR ur.valid_from <= NOW()")
                .contains("ur.valid_until IS NULL OR ur.valid_until > NOW()");
        // A21：角色-权限不再带时效，平台超管判定只看用户-角色窗口
        assertThat(String.join(" ", select.value())).doesNotContain("rp.valid_from")
                .doesNotContain("rp.valid_until");
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
