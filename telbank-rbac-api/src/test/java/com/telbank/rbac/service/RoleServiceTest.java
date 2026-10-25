package com.telbank.rbac.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.RoleDtos.RoleCreateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleUpdateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色时间模板绑定（A21）：创建/更新时可绑定启用模板，admin 角色不能绑定，列表批量回填模板名。
 */
class RoleServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long TEMPLATE_ID = 7L;

    private final List<SysRole> pageRoles = new ArrayList<>();
    private final AtomicLong roleIds = new AtomicLong(100);
    private RoleService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = system();
        SysTimeTemplate template = nightTemplate();

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
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService = new ManagementContextService(
                currentUserProvider, systemRepository, userRoleRepository);
        SystemScopeGuard guard = new SystemScopeGuard(contextService);

        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "search" -> {
                        var page = (com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysRole>) args[0];
                        page.setTotal(pageRoles.size());
                        yield pageRoles;
                    }
                    case "existsBySystemIdAndCode" -> false;
                    case "findById" -> Optional.of(operator(TEMPLATE_ID));
                    case "save" -> {
                        SysRole role = (SysRole) args[0];
                        if (role.getId() == null) {
                            role.setId(roleIds.incrementAndGet());
                        }
                        yield role;
                    }
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysTimeTemplateMapper templateRepository = TestProxies.proxy(SysTimeTemplateMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> TEMPLATE_ID.equals(args[0]) ? Optional.of(template) : Optional.empty();
                    case "findAllById" -> List.of(template);
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        service = new RoleService(roleRepository, systemRepository, templateRepository, guard);
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void createBindsEnabledTemplate() {
        RoleVO vo = service.create(new RoleCreateRequest(
                CRM_ID, "operator", "运营", 1, 2, "remark", TEMPLATE_ID));

        assertThat(vo.id()).isEqualTo(101L);
        assertThat(vo.timeTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(vo.timeTemplateName()).isEqualTo("夜间窗口");
    }

    @Test
    void createWithoutTemplateIsPermanentRole() {
        RoleVO vo = service.create(new RoleCreateRequest(
                CRM_ID, "viewer", "只读", null, null, null, null));

        assertThat(vo.timeTemplateId()).isNull();
        assertThat(vo.timeTemplateName()).isNull();
        assertThat(vo.status()).isEqualTo(1);
    }

    @Test
    void createRejectsTemplateOnAdminRole() {
        assertThatThrownBy(() -> service.create(new RoleCreateRequest(
                CRM_ID, "admin", "管理员", 1, 0, null, TEMPLATE_ID)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("系统管理员角色不能绑定时间模板");
                });
    }

    @Test
    void createRejectsMissingOrDisabledTemplate() {
        assertThatThrownBy(() -> service.create(new RoleCreateRequest(
                CRM_ID, "operator", "运营", 1, 0, null, 99L)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("时间模板不存在或已停用");
                });
    }

    @Test
    void pageFillsTemplateNamesInBatch() {
        pageRoles.add(operator(TEMPLATE_ID));
        pageRoles.add(operator(null));

        PageResult<RoleVO> result = service.page(null, null, PageQuery.of(1, 20));

        assertThat(result.items()).extracting(RoleVO::timeTemplateId)
                .containsExactly(TEMPLATE_ID, null);
        assertThat(result.items()).extracting(RoleVO::timeTemplateName)
                .containsExactly("夜间窗口", null);
    }

    @Test
    void updateWithNullTemplateUnbindsRole() {
        RoleVO vo = service.update(1L, new RoleUpdateRequest("运营-改名", null, null, null, null));

        assertThat(vo.name()).isEqualTo("运营-改名");
        assertThat(vo.timeTemplateId()).isNull();
        assertThat(vo.timeTemplateName()).isNull();
    }

    // ------------------------------------------------------------------

    private static SysSystem system() {
        SysSystem system = new SysSystem();
        system.setId(CRM_ID);
        system.setCode("crm");
        system.setName("CRM");
        system.setStatus(1);
        system.setIsPlatform(0);
        return system;
    }

    private static SysRole operator(Long timeTemplateId) {
        SysRole role = new SysRole();
        role.setId(1L);
        role.setSystemId(CRM_ID);
        role.setCode("operator");
        role.setName("运营");
        role.setStatus(1);
        role.setSort(1);
        role.setTimeTemplateId(timeTemplateId);
        return role;
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
