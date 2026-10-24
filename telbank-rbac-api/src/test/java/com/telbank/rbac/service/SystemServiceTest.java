package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.SystemDtos.SystemCreateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemUpdateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统接入（契约 §3.2）：创建系统时自动创建默认 admin 角色 + 6 个短权限码 + 授权 + 管理员 user_role；
 * 更新系统可更换管理员；平台系统不可通过接口修改。
 */
class SystemServiceTest {

    private final List<SysSystem> systems = new ArrayList<>();
    private final List<SysRole> roles = new ArrayList<>();
    private final List<SysPermission> permissions = new ArrayList<>();
    private final List<SysRolePermission> rolePermissions = new ArrayList<>();
    private final List<SysUserRole> userRoles = new ArrayList<>();
    private final AtomicLong systemIdSeq = new AtomicLong(9);
    private final AtomicLong roleIdSeq = new AtomicLong(19);
    private final AtomicLong permissionIdSeq = new AtomicLong(99);
    private final AtomicLong userRoleIdSeq = new AtomicLong(39);

    private SystemService service;

    @BeforeEach
    void setUp() {
        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "existsByCode" -> systems.stream()
                            .anyMatch(system -> system.getCode().equals(args[0]));
                    case "save" -> saveSystem((SysSystem) args[0]);
                    case "findById" -> systems.stream()
                            .filter(system -> system.getId().equals(args[0])).findFirst();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> saveRole((SysRole) args[0]);
                    case "findBySystemIdAndCode" -> roles.stream()
                            .filter(role -> role.getSystemId().equals(args[0])
                                    && role.getCode().equals(args[1]))
                            .findFirst();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysPermissionMapper permissionRepository = TestProxies.proxy(SysPermissionMapper.class,
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        SysPermission permission = (SysPermission) args[0];
                        permission.setId(permissionIdSeq.incrementAndGet());
                        permissions.add(permission);
                        return permission;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysRolePermissionMapper rolePermissionRepository = TestProxies.proxy(
                SysRolePermissionMapper.class, (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        SysRolePermission grant = (SysRolePermission) args[0];
                        rolePermissions.add(grant);
                        return grant;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> saveUserRole((SysUserRole) args[0]);
                    case "findBySystemIdAndExternalUserIdAndRoleId" -> userRoles.stream()
                            .filter(relation -> relation.getSystemId().equals(args[0])
                                    && relation.getExternalUserId().equals(args[1])
                                    && relation.getRoleId().equals(args[2]))
                            .findFirst();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        service = new SystemService(systemRepository, roleRepository, permissionRepository,
                rolePermissionRepository, userRoleRepository);
    }

    @Test
    void createSystemOnboardingCreatesAdminRolePermissionsAndUserRole() {
        SystemVO vo = service.create(new SystemCreateRequest(
                "acme", "Acme系统", "region_a", "acme-admin-001", "Acme管理员",
                null, null, "接入测试"));

        assertThat(vo.id()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("acme");
        assertThat(vo.isPlatform()).isEqualTo(0);
        assertThat(vo.adminExternalUserId()).isEqualTo("acme-admin-001");
        assertThat(vo.adminName()).isEqualTo("Acme管理员");

        assertThat(roles).hasSize(1);
        SysRole adminRole = roles.get(0);
        assertThat(adminRole.getId()).isEqualTo(20L);
        assertThat(adminRole.getSystemId()).isEqualTo(10L);
        assertThat(adminRole.getCode()).isEqualTo(SystemService.DEFAULT_ADMIN_ROLE_CODE);
        assertThat(adminRole.getName()).isEqualTo(SystemService.DEFAULT_ADMIN_ROLE_NAME);

        // A 方案：系统接入只创建 1 条通配权限 * + 1 条角色授权
        assertThat(permissions).hasSize(1);
        SysPermission wildcard = permissions.get(0);
        assertThat(wildcard.getSystemId()).isEqualTo(10L);
        assertThat(wildcard.getCode()).isEqualTo(RbacConstants.PERM_WILDCARD);
        assertThat(wildcard.getType()).isEqualTo(2);
        assertThat(wildcard.getStatus()).isEqualTo(1);

        assertThat(rolePermissions).hasSize(1);
        SysRolePermission grant = rolePermissions.get(0);
        assertThat(grant.getSystemId()).isEqualTo(10L);
        assertThat(grant.getRoleId()).isEqualTo(20L);
        assertThat(grant.getPermissionId()).isEqualTo(wildcard.getId());
        assertThat(grant.getStatus()).isEqualTo(1);

        assertThat(userRoles).hasSize(1);
        SysUserRole adminGrant = userRoles.get(0);
        assertThat(adminGrant.getSystemId()).isEqualTo(10L);
        assertThat(adminGrant.getExternalUserId()).isEqualTo("acme-admin-001");
        assertThat(adminGrant.getRoleId()).isEqualTo(20L);
        assertThat(adminGrant.getStatus()).isEqualTo(1);
    }

    @Test
    void duplicateCodeIsRejectedWithConflict() {
        systems.add(system(10L, "acme", 1, 0));

        assertThatThrownBy(() -> service.create(new SystemCreateRequest(
                "acme", "Acme", "region_a", "admin-001", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(409));
    }

    @Test
    void updateSystemChangesAdministratorAndRevokesOldGrant() {
        SysSystem system = system(10L, "acme", 1, 0);
        system.setAdminExternalUserId("old-admin");
        system.setAdminName("旧管理员");
        systems.add(system);
        roles.add(role(20L, 10L, SystemService.DEFAULT_ADMIN_ROLE_CODE));
        userRoles.add(userRole(30L, 10L, "old-admin", 20L, 1));

        SystemVO vo = service.update(10L, new SystemUpdateRequest(
                "Acme2", "region_b", "new-admin", "新管理员", 1, 5, "更新"));

        assertThat(vo.name()).isEqualTo("Acme2");
        assertThat(vo.region()).isEqualTo("region_b");
        assertThat(vo.sort()).isEqualTo(5);
        assertThat(vo.adminExternalUserId()).isEqualTo("new-admin");
        assertThat(vo.adminName()).isEqualTo("新管理员");
        assertThat(userRoles).hasSize(2);
        assertThat(userRoles).filteredOn(relation -> "old-admin".equals(relation.getExternalUserId()))
                .singleElement()
                .satisfies(relation -> assertThat(relation.getStatus()).isZero());
        assertThat(userRoles).filteredOn(relation -> "new-admin".equals(relation.getExternalUserId()))
                .singleElement()
                .satisfies(relation -> {
                    assertThat(relation.getStatus()).isEqualTo(1);
                    assertThat(relation.getRoleId()).isEqualTo(20L);
                });
    }

    @Test
    void platformSystemCannotBeManagedThroughApi() {
        systems.add(system(4L, "platform", 1, 1));

        assertThatThrownBy(() -> service.update(4L, new SystemUpdateRequest(
                "platform", "region_a", null, null, 1, 0, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(404));
    }

    @Test
    void listReturnsAdminInformation() {
        // page() 由仓储查询保证只返回业务系统，这里验证 VO 字段包含管理员信息
        SysSystem system = system(10L, "acme", 1, 0);
        system.setAdminExternalUserId("acme-admin-001");
        system.setAdminName("Acme管理员");
        SystemVO vo = SystemService.toVO(system);
        assertThat(vo.adminExternalUserId()).isEqualTo("acme-admin-001");
        assertThat(vo.adminName()).isEqualTo("Acme管理员");
        assertThat(vo.isPlatform()).isZero();
    }

    // ------------------------------------------------------------------

    private SysSystem saveSystem(SysSystem system) {
        if (system.getId() == null) {
            system.setId(systemIdSeq.incrementAndGet());
        }
        systems.add(system);
        return system;
    }

    private SysRole saveRole(SysRole role) {
        if (role.getId() == null) {
            role.setId(roleIdSeq.incrementAndGet());
        }
        roles.add(role);
        return role;
    }

    private SysUserRole saveUserRole(SysUserRole relation) {
        if (relation.getId() == null) {
            relation.setId(userRoleIdSeq.incrementAndGet());
        }
        userRoles.removeIf(existing -> existing.getId().equals(relation.getId()));
        userRoles.add(relation);
        return relation;
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

    private static SysRole role(Long id, Long systemId, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setSystemId(systemId);
        role.setCode(code);
        role.setName(code);
        role.setStatus(1);
        return role;
    }

    private static SysUserRole userRole(Long id, Long systemId, String externalUserId,
                                        Long roleId, int status) {
        SysUserRole relation = new SysUserRole();
        relation.setId(id);
        relation.setSystemId(systemId);
        relation.setExternalUserId(externalUserId);
        relation.setRoleId(roleId);
        relation.setStatus(status);
        return relation;
    }
}
