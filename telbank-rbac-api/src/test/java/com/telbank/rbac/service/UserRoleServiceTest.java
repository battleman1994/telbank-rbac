package com.telbank.rbac.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleCreateRequest;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleVO;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户-角色分配（A21）：分配窗口按角色绑定模板在写入时刻计算；无模板角色为永久（null 窗口）。
 */
class UserRoleServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ROLE_ID = 2L;
    private static final Long TEMPLATE_ID = 7L;

    private boolean roleHasTemplate = true;
    private SysUserRole saved;
    private SysTimeTemplate template;
    private RoleTimeWindowService roleTimeWindowService;
    private UserRoleService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = system();
        template = nightTemplate();
        saved = null;

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByCode", "findById" -> Optional.of(crm);
                    case "existsById" -> true;
                    case "findFirstByIsPlatformOrderByIdAsc" -> Optional.empty();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "countEffectiveRoles" -> 1L;
                    case "countEffectivePermission" -> 0L;
                    case "findBySystemIdAndExternalUserIdAndRoleId" -> Optional.ofNullable(saved);
                    case "save" -> {
                        SysUserRole relation = (SysUserRole) args[0];
                        if (relation.getId() == null) {
                            relation.setId(50L);
                        }
                        saved = relation;
                        yield relation;
                    }
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByIdAndSystemId" -> Optional.of(operator());
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysTimeTemplateMapper templateRepository = TestProxies.proxy(SysTimeTemplateMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> TEMPLATE_ID.equals(args[0]) ? Optional.of(template) : Optional.empty();
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        roleTimeWindowService = new RoleTimeWindowService(templateRepository);
        ChangeNotifyService changeNotifyService = new ChangeNotifyService(systemRepository,
                new RbacProperties(), (channel, payload) -> { },
                new LocalCacheManager(Duration.ofMinutes(1), 10), JsonMapper.builder().build());
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService = new ManagementContextService(
                currentUserProvider, systemRepository, userRoleRepository);
        service = new UserRoleService(userRoleRepository, roleRepository, systemRepository,
                changeNotifyService, new SystemScopeGuard(contextService), roleTimeWindowService);
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void assignWithTemplateWritesComputedWindow() {
        Instant expectedFrom = roleTimeWindowService.compute(template).from();
        Instant expectedUntil = roleTimeWindowService.compute(template).until();

        UserRoleVO vo = service.assign(new UserRoleCreateRequest(CRM_ID, "u-crm-001", ROLE_ID, "分配角色"));

        assertThat(saved.getStatus()).isEqualTo(RbacConstants.STATUS_ENABLED);
        assertThat(saved.getAutoRevoke()).isEqualTo(RbacConstants.AUTO_REVOKE_YES);
        assertThat(vo.validUntil()).isEqualTo(expectedUntil);
        assertThat(vo.validFrom()).isBetween(
                expectedFrom.minusSeconds(5), expectedFrom.plusSeconds(5));
    }

    @Test
    void assignWithoutTemplateLeavesPermanentWindow() {
        roleHasTemplate = false;

        UserRoleVO vo = service.assign(new UserRoleCreateRequest(CRM_ID, "u-crm-001", ROLE_ID, "永久角色"));

        assertThat(saved.getValidFrom()).isNull();
        assertThat(saved.getValidUntil()).isNull();
        assertThat(vo.validFrom()).isNull();
        assertThat(vo.validUntil()).isNull();
    }

    // ------------------------------------------------------------------

    private SysRole operator() {
        SysRole role = new SysRole();
        role.setId(ROLE_ID);
        role.setSystemId(CRM_ID);
        role.setCode("operator");
        role.setName("运营");
        role.setStatus(1);
        role.setTimeTemplateId(roleHasTemplate ? TEMPLATE_ID : null);
        return role;
    }

    private static SysSystem system() {
        SysSystem system = new SysSystem();
        system.setId(CRM_ID);
        system.setCode("crm");
        system.setName("CRM");
        system.setStatus(1);
        system.setIsPlatform(0);
        return system;
    }

    private static SysTimeTemplate nightTemplate() {
        SysTimeTemplate template = new SysTimeTemplate();
        template.setId(TEMPLATE_ID);
        template.setName("夜间窗口");
        template.setTimezone("Asia/Shanghai");
        template.setStartDayOffset(0);
        template.setStartTime(LocalTime.of(19, 0));
        template.setEndDayOffset(1);
        template.setEndTime(LocalTime.of(7, 0));
        template.setStatus(1);
        return template;
    }
}
