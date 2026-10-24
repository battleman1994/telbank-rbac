package com.telbank.rbac.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.repository.SysPageActionRepository;
import com.telbank.rbac.domain.repository.SysPermissionRepository;
import com.telbank.rbac.domain.repository.SysRolePermissionRepository;
import com.telbank.rbac.domain.repository.SysRoleRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;
import com.telbank.rbac.service.ChangeNotifyService;
import com.telbank.rbac.service.PageActionService;
import com.telbank.rbac.service.PermissionService;
import com.telbank.rbac.service.PlatformAdminService;
import com.telbank.rbac.service.RolePermissionService;
import com.telbank.rbac.service.RoleService;
import com.telbank.rbac.service.UserRoleService;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionUpdateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleUpdateRequest;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 严格系统隔离（含超管）：by-id 接口加载实体后必须校验 entity.systemId == 管理上下文；
 * 列表接口 systemId 缺省收敛为上下文、显式指定其他系统 403。
 */
class CrossSystemByIdAccessTest {

    private static final Long CRM_ID = 1L;
    private static final Long ERP_ID = 2L;
    private static final Long PLATFORM_ID = 4L;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ChangeNotifyService changeNotifyService =
            new ChangeNotifyService(null, null, null, null, null) {
            };
    private final LocalCacheManager cacheManager = new LocalCacheManager(Duration.ofMinutes(1), 10);

    private SysSystemRepository systemRepository;
    private SystemScopeGuard guard;

    @BeforeEach
    void setUp() {
        SysSystem crm = system(CRM_ID, "crm", 1, 0);
        SysSystem erp = system(ERP_ID, "erp", 1, 0);
        SysSystem platform = system(PLATFORM_ID, "platform", 1, 1);
        Map<String, SysSystem> byCode = Map.of("crm", crm, "erp", erp, "platform", platform);
        systemRepository = TestProxies.proxy(SysSystemRepository.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return Optional.ofNullable(byCode.get((String) args[0]));
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.of(platform);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleRepository userRoleRepository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("countEffectiveRoles".equals(method.getName())) {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        return systemId == CRM_ID && "crm-admin-001".equals(userId) ? 1L : 0L;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        String code = (String) args[2];
                        return systemId == PLATFORM_ID && "superadmin".equals(userId) && "*".equals(code)
                                ? 1L : 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        guard = new SystemScopeGuard(contextService);

        // 连平台超管也必须遵守系统上下文
        SecurityTestSupport.authenticate("superadmin");
        SecurityTestSupport.requestWithSystemCode("crm");
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void roleByIdInOtherSystemIsForbidden() {
        SysRoleRepository roleRepository = roleRepository(role(9L, ERP_ID, "admin"));
        RoleService service = new RoleService(roleRepository, systemRepository, guard);

        assertForbidden(() -> service.update(9L, new RoleUpdateRequest("hacked", 1, 0, null)));
    }

    @Test
    void roleListExplicitOtherSystemIsForbiddenAndDefaultsToContext() {
        java.util.concurrent.atomic.AtomicReference<Long> searchedSystemId =
                new java.util.concurrent.atomic.AtomicReference<>();
        SysRoleRepository roleRepository = TestProxies.proxy(SysRoleRepository.class,
                (proxy, method, args) -> {
                    if ("search".equals(method.getName())) {
                        searchedSystemId.set((Long) args[0]);
                        return Page.empty(PageRequest.of(0, 20));
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        RoleService service = new RoleService(roleRepository, systemRepository, guard);

        assertForbidden(() -> service.page(ERP_ID, null, PageQuery.of(null, null)));

        service.page(null, null, PageQuery.of(null, null));
        assertThat(searchedSystemId.get()).isEqualTo(CRM_ID);
    }

    @Test
    void permissionByIdInOtherSystemIsForbidden() {
        SysPermissionRepository permissionRepository = TestProxies.proxy(SysPermissionRepository.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Long.valueOf(19L).equals(args[0])
                                ? Optional.of(permission(19L, ERP_ID, "order:list"))
                                : Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PermissionService service = new PermissionService(permissionRepository, systemRepository,
                cacheManager, changeNotifyService, jsonMapper, guard);

        assertForbidden(() -> service.get(19L));
    }

    @Test
    void pageActionByIdInOtherSystemIsForbidden() {
        SysPageActionRepository pageActionRepository = TestProxies.proxy(SysPageActionRepository.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Long.valueOf(19L).equals(args[0])
                                ? Optional.of(pageAction(19L, ERP_ID))
                                : Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PageActionService service = new PageActionService(pageActionRepository, systemRepository,
                cacheManager, changeNotifyService, jsonMapper, guard);

        assertForbidden(() -> service.update(19L, new PageActionUpdateRequest(
                "purchase:list", "purchase:create", "新建", "default", "openDialog", null, 10, 1)));
    }

    @Test
    void userRoleByIdInOtherSystemIsForbidden() {
        SysUserRoleRepository userRoleRepository = TestProxies.proxy(SysUserRoleRepository.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Long.valueOf(19L).equals(args[0])
                                ? Optional.of(relation(19L, ERP_ID))
                                : Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysRoleRepository roleRepository = roleRepository(role(9L, ERP_ID, "admin"));
        UserRoleService service = new UserRoleService(userRoleRepository, roleRepository,
                systemRepository, changeNotifyService, guard);

        assertForbidden(() -> service.revoke(19L));
    }

    @Test
    void rolePermissionGrantsForOtherSystemRoleIsForbidden() {
        SysRoleRepository roleRepository = roleRepository(role(9L, ERP_ID, "admin"));
        RolePermissionService service = new RolePermissionService(roleRepository,
                TestProxies.noop(SysPermissionRepository.class),
                TestProxies.noop(SysRolePermissionRepository.class),
                changeNotifyService, guard);

        assertForbidden(() -> service.grants(9L));
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("cross-system");
                });
    }

    private static SysRoleRepository roleRepository(SysRole role) {
        return TestProxies.proxy(SysRoleRepository.class, (proxy, method, args) -> {
            if ("findById".equals(method.getName())) {
                return role.getId().equals(args[0]) ? Optional.of(role) : Optional.empty();
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
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

    private static SysPermission permission(Long id, Long systemId, String code) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setSystemId(systemId);
        permission.setCode(code);
        permission.setName(code);
        permission.setType(2);
        permission.setStatus(1);
        return permission;
    }

    private static SysPageAction pageAction(Long id, Long systemId) {
        SysPageAction action = new SysPageAction();
        action.setId(id);
        action.setSystemId(systemId);
        action.setPageCode("purchase:list");
        action.setCode("purchase:create");
        action.setText("新建");
        action.setAction("openDialog");
        action.setStatus(1);
        return action;
    }

    private static SysUserRole relation(Long id, Long systemId) {
        SysUserRole relation = new SysUserRole();
        relation.setId(id);
        relation.setSystemId(systemId);
        relation.setExternalUserId("erp-user");
        relation.setRoleId(3L);
        relation.setStatus(1);
        return relation;
    }
}
