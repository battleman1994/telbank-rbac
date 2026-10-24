package com.telbank.rbac.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysSystemApplication;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysSystemApplicationMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportRequest;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportResponse;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveResponse;
import com.telbank.rbac.web.dto.SystemApplicationDtos.RejectRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.SystemApplicationVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK 系统接入上报 + 平台超管审核（A16/A17）：
 * 单条申请记录最新状态、上报幂等、四种状态 upsert、approve 复用系统接入创建系统并返回凭据。
 */
class SystemApplicationServiceTest {

    private final List<SysSystemApplication> applications = new ArrayList<>();
    private final List<SysSystem> systems = new ArrayList<>();
    private final List<SysRole> roles = new ArrayList<>();
    private final List<SysPermission> permissions = new ArrayList<>();
    private final List<SysRolePermission> grants = new ArrayList<>();
    private final List<SysUserRole> userRoles = new ArrayList<>();
    private final AtomicLong applicationIdSeq = new AtomicLong(0);
    private final AtomicLong systemIdSeq = new AtomicLong(100);
    private final AtomicLong roleIdSeq = new AtomicLong(200);
    private final AtomicLong permissionIdSeq = new AtomicLong(300);
    private final AtomicLong userRoleIdSeq = new AtomicLong(400);

    private SystemApplicationService service;

    @BeforeEach
    void setUp() {
        SysSystemApplicationMapper applicationRepository = TestProxies.proxy(SysSystemApplicationMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findBySystemCode" -> applications.stream()
                            .filter(application -> application.getSystemCode().equals(args[0]))
                            .findFirst();
                    case "findById" -> applications.stream()
                            .filter(application -> application.getId() != null
                                    && application.getId().equals(args[0]))
                            .findFirst();
                    case "save" -> saveApplication((SysSystemApplication) args[0]);
                    case "search" -> {
                        @SuppressWarnings("unchecked")
                        Page<SysSystemApplication> page = (Page<SysSystemApplication>) args[0];
                        Integer status = (Integer) args[1];
                        List<SysSystemApplication> filtered = applications.stream()
                                .filter(application -> status == null
                                        || status.equals(application.getStatus()))
                                .sorted(Comparator.comparing(SysSystemApplication::getId).reversed())
                                .toList();
                        page.setTotal(filtered.size());
                        yield filtered;
                    }
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "existsByCode" -> systems.stream()
                            .anyMatch(system -> system.getCode().equals(args[0]));
                    case "save" -> saveSystem((SysSystem) args[0]);
                    case "findById" -> systems.stream()
                            .filter(system -> system.getId().equals(args[0])).findFirst();
                    case "selectById" -> systems.stream()
                            .filter(system -> system.getId().equals(args[0])).findFirst().orElse(null);
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        SysRole role = (SysRole) args[0];
                        role.setId(roleIdSeq.incrementAndGet());
                        roles.add(role);
                        return role;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
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
        SysRolePermissionMapper rolePermissionRepository = TestProxies.proxy(SysRolePermissionMapper.class,
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        grants.add((SysRolePermission) args[0]);
                        return args[0];
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        SysUserRole relation = (SysUserRole) args[0];
                        relation.setId(userRoleIdSeq.incrementAndGet());
                        userRoles.add(relation);
                        return relation;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SystemService systemService = new SystemService(systemRepository, roleRepository,
                permissionRepository, rolePermissionRepository, userRoleRepository);
        service = new SystemApplicationService(applicationRepository, systemRepository, systemService);
    }

    // ------------------------------------------------------------------ 上报

    @Test
    void reportWithoutExistingApplicationCreatesPendingApplication() {
        SystemReportResponse response = service.report(
                new SystemReportRequest("finance", "财务系统", "region_a", "1.0.0", "SDK 上报"),
                "onboarding-app");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.applicationId()).isNotNull();
        assertThat(applications).hasSize(1);
        SysSystemApplication application = applications.get(0);
        assertThat(application.getSystemCode()).isEqualTo("finance");
        assertThat(application.getName()).isEqualTo("财务系统");
        assertThat(application.getRegion()).isEqualTo("region_a");
        assertThat(application.getSource()).isEqualTo(RbacConstants.APPLICATION_SOURCE_SDK);
        assertThat(application.getAppKey()).isEqualTo("onboarding-app");
        assertThat(application.getSdkVersion()).isEqualTo("1.0.0");
        assertThat(application.getRemark()).isEqualTo("SDK 上报");
        assertThat(application.getStatus()).isEqualTo(RbacConstants.APPLICATION_STATUS_PENDING);
        assertThat(application.getReviewedBy()).isNull();
        assertThat(application.getReviewedAt()).isNull();
    }

    @Test
    void reportPendingApplicationUpdatesLatestInformationWithoutDuplicating() {
        SysSystemApplication application = pendingApplication("finance");
        application.setSdkVersion("0.9.0");
        application.setRemark("旧备注");

        SystemReportResponse response = service.report(
                new SystemReportRequest("finance", "财务系统V2", "region_b", "1.1.0", "新备注"),
                "onboarding-app");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(applications).hasSize(1);
        assertThat(application.getName()).isEqualTo("财务系统V2");
        assertThat(application.getRegion()).isEqualTo("region_b");
        assertThat(application.getSdkVersion()).isEqualTo("1.1.0");
        assertThat(application.getRemark()).isEqualTo("新备注");
        assertThat(application.getStatus()).isEqualTo(RbacConstants.APPLICATION_STATUS_PENDING);
    }

    @Test
    void reportRejectedApplicationResetsToPendingAndClearsReviewFields() {
        SysSystemApplication application = pendingApplication("finance");
        application.setStatus(RbacConstants.APPLICATION_STATUS_REJECTED);
        application.setRejectReason("资料不完整");
        application.setReviewedBy("superadmin");
        application.setReviewedAt(LocalDateTime.now());
        application.setSystemId(99L);

        SystemReportResponse response = service.report(
                new SystemReportRequest("finance", "财务系统", "region_a", "2.0.0", null),
                "onboarding-app");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(application.getStatus()).isEqualTo(RbacConstants.APPLICATION_STATUS_PENDING);
        assertThat(application.getRejectReason()).isNull();
        assertThat(application.getReviewedBy()).isNull();
        assertThat(application.getReviewedAt()).isNull();
        assertThat(application.getSystemId()).isNull();
        assertThat(application.getSdkVersion()).isEqualTo("2.0.0");
    }

    @Test
    void reportApprovedApplicationIsIdempotentAndDoesNotOverwrite() {
        SysSystemApplication application = pendingApplication("finance");
        application.setStatus(RbacConstants.APPLICATION_STATUS_APPROVED);
        application.setSystemId(10L);

        SystemReportResponse response = service.report(
                new SystemReportRequest("finance", "改名不应生效", "region_b", "9.9.9", "x"),
                "onboarding-app");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.applicationId()).isEqualTo(application.getId());
        assertThat(applications).hasSize(1);
        assertThat(application.getName()).isEqualTo("Acme系统");
        assertThat(application.getRegion()).isEqualTo("region_a");
    }

    @Test
    void reportExistingSystemWithoutApplicationIsConflict() {
        systems.add(system("crm"));

        assertThatThrownBy(() -> service.report(
                new SystemReportRequest("crm", "CRM", "region_a", null, null), "onboarding-app"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("system already exists");
                });
        assertThat(applications).isEmpty();
    }

    // ------------------------------------------------------------------ 审批

    @Test
    void approveCreatesSystemAndReturnsAssignedAppCredentials() {
        SysSystemApplication application = pendingApplication("acme");

        ApproveResponse response = service.approve(application.getId(),
                new ApproveRequest("acme-admin-001", "Acme管理员"), "superadmin");

        assertThat(response.applicationId()).isEqualTo(application.getId());
        assertThat(response.systemId()).isNotNull();
        assertThat(response.systemCode()).isEqualTo("acme");
        assertThat(response.appKey()).isEqualTo("acme");
        assertThat(response.appSecret()).hasSize(SystemService.APP_SECRET_LENGTH);

        assertThat(application.getStatus()).isEqualTo(RbacConstants.APPLICATION_STATUS_APPROVED);
        assertThat(application.getSystemId()).isEqualTo(response.systemId());
        assertThat(application.getReviewedBy()).isEqualTo("superadmin");
        assertThat(application.getReviewedAt()).isNotNull();
        assertThat(application.getName()).isEqualTo("Acme系统");
        assertThat(application.getRejectReason()).isNull();

        assertThat(systems).hasSize(1);
        SysSystem system = systems.get(0);
        assertThat(system.getCode()).isEqualTo("acme");
        assertThat(system.getAdminExternalUserId()).isEqualTo("acme-admin-001");
        assertThat(system.getAppKey()).isEqualTo("acme");
        assertThat(system.getAppSecret()).isEqualTo(response.appSecret());

        assertThat(roles).singleElement()
                .satisfies(role -> assertThat(role.getCode()).isEqualTo(SystemService.DEFAULT_ADMIN_ROLE_CODE));
        assertThat(permissions).singleElement()
                .satisfies(permission -> assertThat(permission.getCode()).isEqualTo(RbacConstants.PERM_WILDCARD));
        assertThat(grants).hasSize(1);
        assertThat(userRoles).singleElement()
                .satisfies(relation -> assertThat(relation.getExternalUserId()).isEqualTo("acme-admin-001"));
    }

    @Test
    void approveNonPendingApplicationIsConflict() {
        SysSystemApplication application = pendingApplication("acme");
        application.setStatus(RbacConstants.APPLICATION_STATUS_APPROVED);

        assertThatThrownBy(() -> service.approve(application.getId(),
                new ApproveRequest("admin-001", null), "superadmin"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(409));
        assertThat(systems).isEmpty();
    }

    @Test
    void approveMissingApplicationIsNotFound() {
        assertThatThrownBy(() -> service.approve(404L,
                new ApproveRequest("admin-001", null), "superadmin"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(404));
    }

    @Test
    void rejectPendingApplicationUpdatesStatusAndReason() {
        SysSystemApplication application = pendingApplication("acme");

        SystemApplicationVO vo = service.reject(application.getId(),
                new RejectRequest("信息不完整"), "superadmin");

        assertThat(vo.status()).isEqualTo("REJECTED");
        assertThat(vo.rejectReason()).isEqualTo("信息不完整");
        assertThat(vo.reviewedBy()).isEqualTo("superadmin");
        assertThat(vo.reviewedAt()).isNotNull();
        assertThat(application.getStatus()).isEqualTo(RbacConstants.APPLICATION_STATUS_REJECTED);
        assertThat(application.getReviewedBy()).isEqualTo("superadmin");
        assertThat(applications).hasSize(1);
        assertThat(systems).isEmpty();
    }

    @Test
    void rejectNonPendingApplicationIsConflict() {
        SysSystemApplication application = pendingApplication("acme");
        application.setStatus(RbacConstants.APPLICATION_STATUS_REJECTED);

        assertThatThrownBy(() -> service.reject(application.getId(),
                new RejectRequest("again"), "superadmin"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------ 分页

    @Test
    void pageFiltersByStatusAndMapsStatusNames() {
        pendingApplication("pending-one");
        pendingApplication("approved-one")
                .setStatus(RbacConstants.APPLICATION_STATUS_APPROVED);
        pendingApplication("rejected-one")
                .setStatus(RbacConstants.APPLICATION_STATUS_REJECTED);

        PageResult<SystemApplicationVO> pending = service.page("PENDING", PageQuery.of(1, 20));
        assertThat(pending.total()).isEqualTo(1);
        assertThat(pending.items()).singleElement()
                .satisfies(vo -> assertThat(vo.status()).isEqualTo("PENDING"));

        PageResult<SystemApplicationVO> all = service.page(null, PageQuery.of(1, 20));
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).extracting(SystemApplicationVO::status)
                .containsExactlyInAnyOrder("PENDING", "APPROVED", "REJECTED");

        assertThat(service.page("APPROVED", PageQuery.of(1, 20)).items())
                .singleElement()
                .satisfies(vo -> assertThat(vo.status()).isEqualTo("APPROVED"));
    }

    @Test
    void pageRejectsInvalidStatus() {
        assertThatThrownBy(() -> service.page("UNKNOWN", PageQuery.of(1, 20)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------

    private SysSystemApplication pendingApplication(String code) {
        SysSystemApplication application = new SysSystemApplication();
        application.setSystemCode(code);
        application.setName("Acme系统");
        application.setRegion("region_a");
        application.setSource(RbacConstants.APPLICATION_SOURCE_SDK);
        application.setAppKey("onboarding-app");
        application.setSdkVersion("1.0.0");
        application.setRemark("SDK 上报");
        application.setStatus(RbacConstants.APPLICATION_STATUS_PENDING);
        return saveApplication(application);
    }

    private SysSystemApplication saveApplication(SysSystemApplication application) {
        if (application.getId() == null) {
            application.setId(applicationIdSeq.incrementAndGet());
        }
        applications.removeIf(existing -> existing.getId().equals(application.getId()));
        applications.add(application);
        return application;
    }

    private SysSystem saveSystem(SysSystem system) {
        if (system.getId() == null) {
            system.setId(systemIdSeq.incrementAndGet());
        }
        systems.add(system);
        return system;
    }

    private static SysSystem system(String code) {
        SysSystem system = new SysSystem();
        system.setCode(code);
        system.setName(code);
        system.setRegion("region_a");
        system.setStatus(1);
        system.setIsPlatform(0);
        return system;
    }
}
