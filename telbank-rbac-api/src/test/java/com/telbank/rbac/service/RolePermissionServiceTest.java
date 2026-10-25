package com.telbank.rbac.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionGrantRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionRevokeRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionsVO;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色授权保护：A18 下 admin 角色默认拥有全部权限，grant/revoke 返回 400；
 * Task 5 下非 admin 角色禁止手工授予通配权限 {@code *}；普通角色不受影响。
 */
class RolePermissionServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ADMIN_ROLE_ID = 1L;
    private static final Long OPERATOR_ROLE_ID = 2L;
    private static final Long PERMISSION_ID = 5L;
    private static final Long WILDCARD_PERMISSION_ID = 6L;

    private final Map<String, SysRolePermission> relations = new ConcurrentHashMap<>();
    private final Set<Long> savedPermissionIds = new LinkedHashSet<>();
    private RolePermissionService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = system(CRM_ID, "crm");
        SysRole adminRole = role(ADMIN_ROLE_ID, "admin");
        SysRole operatorRole = role(OPERATOR_ROLE_ID, "operator");

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return Optional.of(crm);
                    }
                    if ("findById".equals(method.getName())) {
                        return Optional.of(crm);
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    if ("countEffectiveRoles".equals(method.getName())) {
                        return 1L;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        return 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        SystemScopeGuard guard = new SystemScopeGuard(contextService);

        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        Long roleId = (Long) args[0];
                        if (ADMIN_ROLE_ID.equals(roleId)) {
                            return Optional.of(adminRole);
                        }
                        if (OPERATOR_ROLE_ID.equals(roleId)) {
                            return Optional.of(operatorRole);
                        }
                        return Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysPermissionMapper permissionRepository = TestProxies.proxy(SysPermissionMapper.class,
                (proxy, method, args) -> {
                    if ("findByIdAndSystemId".equals(method.getName())) {
                        Long permissionId = (Long) args[0];
                        Long systemId = (Long) args[1];
                        if (!CRM_ID.equals(systemId)) {
                            return Optional.empty();
                        }
                        if (PERMISSION_ID.equals(permissionId)) {
                            return Optional.of(permission());
                        }
                        if (WILDCARD_PERMISSION_ID.equals(permissionId)) {
                            return Optional.of(wildcardPermission());
                        }
                        return Optional.empty();
                    }
                    if ("findAllById".equals(method.getName())) {
                        return List.of(permission());
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysRolePermissionMapper rolePermissionRepository = TestProxies.proxy(SysRolePermissionMapper.class,
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByRoleIdAndPermissionId":
                            return Optional.ofNullable(relations.get(key((Long) args[0], (Long) args[1])));
                        case "findByRoleIdOrderByPermissionIdAsc":
                            return relations.values().stream()
                                    .filter(relation -> relation.getRoleId().equals(args[0]))
                                    .toList();
                        case "saveAll": {
                            List<SysRolePermission> saved = new ArrayList<>();
                            for (Object entity : (Iterable<?>) args[0]) {
                                SysRolePermission relation = (SysRolePermission) entity;
                                relations.put(key(relation.getRoleId(), relation.getPermissionId()), relation);
                                savedPermissionIds.add(relation.getPermissionId());
                                saved.add(relation);
                            }
                            return saved;
                        }
                        case "save": {
                            SysRolePermission relation = (SysRolePermission) args[0];
                            relations.put(key(relation.getRoleId(), relation.getPermissionId()), relation);
                            return relation;
                        }
                        default:
                            return TestProxies.defaultValue(method.getReturnType());
                    }
                });
        ChangeNotifyService changeNotifyService = new ChangeNotifyService(systemRepository,
                new RbacProperties(), (channel, payload) -> { },
                new LocalCacheManager(Duration.ofMinutes(1), 10), JsonMapper.builder().build());
        service = new RolePermissionService(roleRepository, permissionRepository,
                rolePermissionRepository, changeNotifyService, guard);
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void grantToAdminRoleIsRejectedWithGuidance() {
        assertThatThrownBy(() -> service.grant(ADMIN_ROLE_ID,
                new RolePermissionGrantRequest(List.of(PERMISSION_ID), "尝试授权")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("系统管理员角色默认拥有全部权限");
                });
        assertThat(relations).isEmpty();
        assertThat(savedPermissionIds).isEmpty();
    }

    @Test
    void revokeFromAdminRoleIsRejectedWithGuidance() {
        relations.put(key(ADMIN_ROLE_ID, PERMISSION_ID), relation(1L, 1));

        assertThatThrownBy(() -> service.revoke(ADMIN_ROLE_ID,
                new RolePermissionRevokeRequest(List.of(PERMISSION_ID))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("系统管理员角色默认拥有全部权限");
                });
        assertThat(relations.get(key(ADMIN_ROLE_ID, PERMISSION_ID)).getStatus()).isEqualTo(1);
    }

    @Test
    void grantWildcardToNonAdminRoleIsRejectedWithGuidance() {
        assertThatThrownBy(() -> service.grant(OPERATOR_ROLE_ID,
                new RolePermissionGrantRequest(List.of(WILDCARD_PERMISSION_ID), "尝试授权通配")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("通配");
                    assertThat(exception.getMessage()).contains("*");
                });
        assertThat(relations).isEmpty();
        assertThat(savedPermissionIds).isEmpty();
    }

    @Test
    void grantBatchContainingWildcardWritesNothing() {
        assertThatThrownBy(() -> service.grant(OPERATOR_ROLE_ID,
                new RolePermissionGrantRequest(List.of(PERMISSION_ID, WILDCARD_PERMISSION_ID),
                        "批量授权含通配")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("通配");
                });
        assertThat(relations).isEmpty();
        assertThat(savedPermissionIds).isEmpty();
    }

    @Test
    void adminRoleProtectionTakesPrecedenceOverWildcardCheck() {
        assertThatThrownBy(() -> service.grant(ADMIN_ROLE_ID,
                new RolePermissionGrantRequest(List.of(WILDCARD_PERMISSION_ID), "尝试授权")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("系统管理员角色默认拥有全部权限");
                });
        assertThat(relations).isEmpty();
        assertThat(savedPermissionIds).isEmpty();
    }

    @Test
    void grantToOperatorRoleStillWorks() {
        RolePermissionsVO result = service.grant(OPERATOR_ROLE_ID,
                new RolePermissionGrantRequest(List.of(PERMISSION_ID), "运营授权"));

        assertThat(savedPermissionIds).containsExactly(PERMISSION_ID);
        SysRolePermission relation = relations.get(key(OPERATOR_ROLE_ID, PERMISSION_ID));
        assertThat(relation).isNotNull();
        assertThat(relation.getStatus()).isEqualTo(1);
        assertThat(relation.getGrantReason()).isEqualTo("运营授权");
        assertThat(result.roleId()).isEqualTo(OPERATOR_ROLE_ID);
        assertThat(result.grants()).singleElement()
                .satisfies(grant -> {
                    assertThat(grant.permissionId()).isEqualTo(PERMISSION_ID);
                    assertThat(grant.code()).isEqualTo("order:list");
                });
    }

    @Test
    void revokeFromOperatorRoleStillWorks() {
        relations.put(key(OPERATOR_ROLE_ID, PERMISSION_ID), relation(OPERATOR_ROLE_ID, 1));

        RolePermissionsVO result = service.revoke(OPERATOR_ROLE_ID,
                new RolePermissionRevokeRequest(List.of(PERMISSION_ID)));

        assertThat(relations.get(key(OPERATOR_ROLE_ID, PERMISSION_ID)).getStatus()).isZero();
        assertThat(result.grants()).singleElement()
                .satisfies(grant -> assertThat(grant.status()).isZero());
    }

    // ------------------------------------------------------------------

    private static String key(Long roleId, Long permissionId) {
        return roleId + ":" + permissionId;
    }

    private static SysSystem system(Long id, String code) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(code);
        system.setRegion("region_a");
        system.setStatus(1);
        system.setIsPlatform(0);
        return system;
    }

    private static SysRole role(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setSystemId(CRM_ID);
        role.setCode(code);
        role.setName(code);
        role.setStatus(1);
        return role;
    }

    private static SysPermission permission() {
        SysPermission permission = new SysPermission();
        permission.setId(PERMISSION_ID);
        permission.setSystemId(CRM_ID);
        permission.setCode("order:list");
        permission.setName("订单列表");
        permission.setStatus(1);
        return permission;
    }

    private static SysPermission wildcardPermission() {
        SysPermission permission = new SysPermission();
        permission.setId(WILDCARD_PERMISSION_ID);
        permission.setSystemId(CRM_ID);
        permission.setCode("*");
        permission.setName("全部权限（通配）");
        permission.setStatus(1);
        return permission;
    }

    private static SysRolePermission relation(Long roleId, int status) {
        SysRolePermission relation = new SysRolePermission();
        relation.setId(1L);
        relation.setSystemId(CRM_ID);
        relation.setRoleId(roleId);
        relation.setPermissionId(PERMISSION_ID);
        relation.setStatus(status);
        return relation;
    }
}
