package com.telbank.rbac.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysAccessApplication;
import com.telbank.rbac.domain.entity.SysAccessApplicationRole;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysAccessApplicationMapper;
import com.telbank.rbac.domain.mapper.SysAccessApplicationRoleMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.domain.mapper.row.AccessRolePermissionRow;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.AccessApplicationDtos.ApplicationVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.CreateRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.OptionsVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RoleOptionVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RolePermissionVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RoleVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 申请人侧申请接口：options 过滤 admin/停用角色、page/detail 强制 system+user 归属与状态解析。
 */
class AccessApplicationServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ERP_ID = 2L;
    private static final Long ADMIN_ROLE_ID = 10L;
    private static final Long OPERATOR_ROLE_ID = 11L;
    private static final Long AUDITOR_ROLE_ID = 12L;
    private static final Long INTERN_ROLE_ID = 13L;
    private static final Long PENDING_APPLICATION_ID = 100L;
    private static final String USER = "u-crm-001";
    private static final String OTHER_USER = "u-crm-002";
    private static final String PENDING_NO = "RA-20261025-A1B2C3";
    private static final String APPROVED_NO = "RA-20261024-Z9Y8X7";
    private static final String TEMPLATE =
            "http://localhost:8090/approval/access?applicationNo={applicationNo}";

    private final Map<String, SysAccessApplication> applications = new ConcurrentHashMap<>();
    private final Map<Long, List<SysAccessApplicationRole>> snapshots = new ConcurrentHashMap<>();
    private final List<SysAccessApplication> searchResults = new ArrayList<>();
    private final List<SearchCall> searchCalls = new ArrayList<>();
    private final List<PermissionPreviewCall> permissionPreviewCalls = new ArrayList<>();
    private final List<List<Long>> snapshotCalls = new ArrayList<>();
    private final List<RoleQueryCall> roleQueryCalls = new ArrayList<>();
    private final List<Long> ownedRoleSystemIds = new ArrayList<>();
    private final List<String> ownedRoleUserIds = new ArrayList<>();
    private final List<Long> ownedRoleIds = new ArrayList<>();
    private final List<Long> roleLookupRoleIds = new ArrayList<>();
    private final List<Long> roleLookupSystemIds = new ArrayList<>();
    private final List<PendingCall> pendingCalls = new ArrayList<>();
    private final List<String> savedApplicationNos = new ArrayList<>();
    private final List<String> existsApplicationNoCalls = new ArrayList<>();
    private final AtomicLong applicationIds = new AtomicLong(200L);
    private final AtomicLong snapshotIds = new AtomicLong(1000L);
    private boolean duplicateApplicationNo;
    private boolean applicationSaveConflict;

    private SysAccessApplicationMapper applicationRepository;
    private SysAccessApplicationRoleMapper applicationRoleRepository;
    private SysRoleMapper roleRepository;
    private SysUserRoleMapper userRoleRepository;
    private SysRolePermissionMapper rolePermissionRepository;
    private SysSystemMapper systemRepository;
    private AccessApplicationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ownedRoleIds.add(OPERATOR_ROLE_ID);
        SysSystem crm = system(CRM_ID, "crm", "CRM系统");
        SysSystem erp = system(ERP_ID, "erp", "ERP系统");
        SysRole admin = role(ADMIN_ROLE_ID, "admin", "管理员", RbacConstants.STATUS_ENABLED, 1);
        SysRole operator = role(OPERATOR_ROLE_ID, "operator", "CRM运营", RbacConstants.STATUS_ENABLED, 2);
        SysRole auditor = role(AUDITOR_ROLE_ID, "auditor", "审计员", RbacConstants.STATUS_ENABLED, 3);
        SysRole intern = role(INTERN_ROLE_ID, "intern", "实习生", 0, 4);
        Map<Long, List<AccessRolePermissionRow>> previewRows = Map.of(
                ADMIN_ROLE_ID, List.of(
                        permissionRow(ADMIN_ROLE_ID, 101L, "*", "全部权限", 2)),
                OPERATOR_ROLE_ID, List.of(
                        permissionRow(OPERATOR_ROLE_ID, 102L, "order:read", "查看订单", 2)),
                AUDITOR_ROLE_ID, List.of());

        systemRepository = TestProxies.proxy(SysSystemMapper.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByCode":
                    if ("crm".equals(args[0])) {
                        return Optional.of(crm);
                    }
                    if ("erp".equals(args[0])) {
                        return Optional.of(erp);
                    }
                    return Optional.empty();
                case "findById":
                    if (CRM_ID.equals(args[0])) {
                        return Optional.of(crm);
                    }
                    if (ERP_ID.equals(args[0])) {
                        return Optional.of(erp);
                    }
                    return Optional.empty();
                case "findFirstByIsPlatformOrderByIdAsc":
                    return Optional.empty();
                default:
                    return TestProxies.defaultValue(method.getReturnType());
            }
        });
        userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findActiveRoleIds":
                    ownedRoleSystemIds.add((Long) args[0]);
                    ownedRoleUserIds.add((String) args[1]);
                    return List.copyOf(ownedRoleIds);
                case "countEffectiveRoles":
                    return 1L;
                case "countEffectivePermission":
                    return 0L;
                default:
                    return TestProxies.defaultValue(method.getReturnType());
            }
        });
        roleRepository = TestProxies.proxy(SysRoleMapper.class, (proxy, method, args) -> {
            if ("findAllBySystemIdAndStatusOrderBySortAscIdAsc".equals(method.getName())) {
                Long systemId = (Long) args[0];
                Integer status = (Integer) args[1];
                roleQueryCalls.add(new RoleQueryCall(systemId, status));
                return List.of(admin, operator, auditor, intern).stream()
                        .filter(role -> status == null || status.equals(role.getStatus()))
                        .toList();
            }
            if ("findByIdAndSystemId".equals(method.getName())) {
                Long roleId = (Long) args[0];
                Long systemId = (Long) args[1];
                roleLookupRoleIds.add(roleId);
                roleLookupSystemIds.add(systemId);
                if (!CRM_ID.equals(systemId)) {
                    return Optional.empty();
                }
                return List.of(admin, operator, auditor, intern).stream()
                        .filter(role -> role.getId().equals(roleId))
                        .findFirst();
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
        rolePermissionRepository = TestProxies.proxy(SysRolePermissionMapper.class, (proxy, method, args) -> {
            if ("findActivePermissionRows".equals(method.getName())) {
                Long systemId = (Long) args[0];
                List<Long> roleIds = List.copyOf((Collection<Long>) args[1]);
                permissionPreviewCalls.add(new PermissionPreviewCall(systemId, roleIds));
                return roleIds.stream()
                        .flatMap(roleId -> previewRows.getOrDefault(roleId, List.of()).stream())
                        .toList();
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
        applicationRepository = TestProxies.proxy(SysAccessApplicationMapper.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByApplicationNo":
                    return Optional.ofNullable(applications.get((String) args[0]));
                case "findPending": {
                    Long systemId = (Long) args[0];
                    String externalUserId = (String) args[1];
                    pendingCalls.add(new PendingCall(systemId, externalUserId));
                    return applications.values().stream()
                            .filter(application -> application.getSystemId().equals(systemId))
                            .filter(application -> externalUserId.equals(application.getExternalUserId()))
                            .filter(application -> Integer.valueOf(
                                    RbacConstants.ACCESS_APPLICATION_STATUS_PENDING)
                                    .equals(application.getStatus()))
                            .findFirst();
                }
                case "existsByApplicationNo": {
                    String applicationNo = (String) args[0];
                    existsApplicationNoCalls.add(applicationNo);
                    return duplicateApplicationNo || applications.containsKey(applicationNo);
                }
                case "save": {
                    SysAccessApplication application = (SysAccessApplication) args[0];
                    if (applicationSaveConflict) {
                        throw new DataIntegrityViolationException("duplicate pending application");
                    }
                    if (application.getId() == null) {
                        application.setId(applicationIds.getAndIncrement());
                    }
                    applications.put(application.getApplicationNo(), application);
                    savedApplicationNos.add(application.getApplicationNo());
                    return application;
                }
                case "findById": {
                    Long id = (Long) args[0];
                    return applications.values().stream()
                            .filter(application -> id.equals(application.getId()))
                            .findFirst();
                }
                case "search": {
                    Page<SysAccessApplication> page = (Page<SysAccessApplication>) args[0];
                    Long systemId = (Long) args[1];
                    String externalUserId = (String) args[2];
                    Integer status = (Integer) args[3];
                    searchCalls.add(new SearchCall(systemId, externalUserId, status,
                            page.getCurrent(), page.getSize()));
                    List<SysAccessApplication> matched = searchResults.stream()
                            .filter(application -> application.getSystemId().equals(systemId))
                            .filter(application -> application.getExternalUserId().equals(externalUserId))
                            .filter(application -> status == null || status.equals(application.getStatus()))
                            .toList();
                    page.setTotal(matched.size());
                    page.setRecords(matched);
                    return matched;
                }
                default:
                    return TestProxies.defaultValue(method.getReturnType());
            }
        });
        applicationRoleRepository = TestProxies.proxy(SysAccessApplicationRoleMapper.class,
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByApplicationIdInOrderByIdAsc": {
                            List<Long> applicationIds = List.copyOf((Collection<Long>) args[0]);
                            snapshotCalls.add(applicationIds);
                            return applicationIds.stream()
                                    .flatMap(id -> snapshots.getOrDefault(id, List.of()).stream())
                                    .toList();
                        }
                        case "save": {
                            SysAccessApplicationRole snapshot = (SysAccessApplicationRole) args[0];
                            if (snapshot.getId() == null) {
                                snapshot.setId(snapshotIds.getAndIncrement());
                            }
                            snapshots.computeIfAbsent(snapshot.getApplicationId(),
                                    id -> new ArrayList<>()).add(snapshot);
                            return snapshot;
                        }
                        default:
                            return TestProxies.defaultValue(method.getReturnType());
                    }
                });

        service = newService(new RbacProperties());
        SecurityTestSupport.authenticate(USER);
        SecurityTestSupport.requestWithSystemCode("crm");
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void options_returnsOnlyApplicableRolesAndActivePermissionPreview() {
        OptionsVO options = service.options();

        assertThat(options.systemId()).isEqualTo(CRM_ID);
        assertThat(options.systemCode()).isEqualTo("crm");
        assertThat(options.systemName()).isEqualTo("CRM系统");
        assertThat(options.approvalUrlTemplate()).isEqualTo(TEMPLATE);
        assertThat(options.roles()).extracting(RoleOptionVO::roleCode)
                .containsExactly("operator", "auditor");
        assertThat(options.roles()).extracting(RoleOptionVO::owned)
                .containsExactly(true, false);
        assertThat(options.roles()).flatExtracting(RoleOptionVO::permissions)
                .extracting(RolePermissionVO::code)
                .containsExactly("order:read");
        assertThat(options.roles().toString()).doesNotContain("admin").doesNotContain("intern");
        assertThat(roleQueryCalls).singleElement().satisfies(call -> {
            assertThat(call.systemId()).isEqualTo(CRM_ID);
            assertThat(call.status()).isEqualTo(RbacConstants.STATUS_ENABLED);
        });
        // 权限预览一次批量查询，且不会带上 admin 角色（避免通配权限暴露）
        assertThat(permissionPreviewCalls).singleElement().satisfies(call -> {
            assertThat(call.systemId()).isEqualTo(CRM_ID);
            assertThat(call.roleIds()).containsExactly(OPERATOR_ROLE_ID, AUDITOR_ROLE_ID);
        });
        assertThat(ownedRoleSystemIds).containsExactly(CRM_ID);
        assertThat(ownedRoleUserIds).containsExactly(USER);
    }

    @Test
    void options_requiresManagementContext() {
        SecurityTestSupport.reset();
        SecurityTestSupport.authenticate(USER);
        SecurityTestSupport.requestWithSystemCode(null);

        assertThatThrownBy(() -> service.options())
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(403);
                    assertThat(exception.getMessage()).isEqualTo("X-System-Code header is required");
                });
    }

    @Test
    void options_returnsTemplateVerbatimAndLeavesApprovalUrlEmptyWhenPlaceholderMissing() {
        applications.put(PENDING_NO, application(PENDING_APPLICATION_ID, PENDING_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        RbacProperties custom = new RbacProperties();
        custom.getAccessApproval().setUrlTemplate("http://approval.local/access");
        AccessApplicationService customService = newService(custom);

        assertThat(customService.options().approvalUrlTemplate())
                .isEqualTo("http://approval.local/access");
        assertThat(customService.detail(PENDING_NO).approvalUrl()).isNull();
    }

    @Test
    void page_scopesToCurrentSystemAndUserAndMapsVos() {
        SysAccessApplication pending = application(PENDING_APPLICATION_ID, PENDING_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
        pending.setReason("季度末需要批量导出订单");
        pending.setCreatedAt(LocalDateTime.of(2026, 10, 25, 1, 20));
        applications.put(PENDING_NO, pending);
        snapshots.put(PENDING_APPLICATION_ID, List.of(
                snapshot(PENDING_APPLICATION_ID, OPERATOR_ROLE_ID, "operator", "CRM运营"),
                snapshot(PENDING_APPLICATION_ID, AUDITOR_ROLE_ID, "auditor", "审计员")));
        searchResults.add(pending);
        searchResults.add(application(101L, APPROVED_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED));

        PageResult<ApplicationVO> result = service.page("pending", PageQuery.of(2, 10));

        assertThat(searchCalls).singleElement().satisfies(call -> {
            assertThat(call.systemId()).isEqualTo(CRM_ID);
            assertThat(call.externalUserId()).isEqualTo(USER);
            assertThat(call.status()).isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
            assertThat(call.page()).isEqualTo(2);
            assertThat(call.size()).isEqualTo(10);
        });
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        ApplicationVO vo = result.items().get(0);
        assertThat(vo.id()).isEqualTo(PENDING_APPLICATION_ID);
        assertThat(vo.applicationNo()).isEqualTo(PENDING_NO);
        assertThat(vo.systemId()).isEqualTo(CRM_ID);
        assertThat(vo.systemCode()).isEqualTo("crm");
        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.roleCount()).isEqualTo(2);
        assertThat(vo.roles()).extracting(RoleVO::roleCode).containsExactly("operator", "auditor");
        assertThat(vo.reason()).isEqualTo("季度末需要批量导出订单");
        assertThat(vo.createdAt()).isEqualTo(Instant.parse("2026-10-25T01:20:00Z"));
        assertThat(vo.approvalUrl()).isEqualTo(TEMPLATE.replace("{applicationNo}", PENDING_NO));
        // 角色快照按申请单批量查询（一次），避免逐单 N+1
        assertThat(snapshotCalls).singleElement()
                .satisfies(applicationIds -> assertThat(applicationIds)
                        .containsExactly(PENDING_APPLICATION_ID));
    }

    @Test
    void page_blankStatusMeansAllAndParsesStatusCaseInsensitively() {
        searchResults.add(application(PENDING_APPLICATION_ID, PENDING_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        searchResults.add(application(101L, APPROVED_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED));

        PageResult<ApplicationVO> all = service.page("  ", PageQuery.of(null, null));

        assertThat(searchCalls.get(0).status()).isNull();
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.page()).isEqualTo(1);
        assertThat(all.size()).isEqualTo(20);
        assertThat(all.items()).extracting(ApplicationVO::status)
                .containsExactly("PENDING", "APPROVED");

        service.page("pEnDiNg", PageQuery.of(1, 20));

        assertThat(searchCalls.get(1).status())
                .isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
    }

    @Test
    void page_passesStatusCodesToSearchForApprovedRejectedAndNull() {
        service.page("APPROVED", PageQuery.of(1, 20));
        service.page("REJECTED", PageQuery.of(1, 20));
        service.page(null, PageQuery.of(1, 20));

        // 与数据库/前端约定：PENDING=0、APPROVED=1、REJECTED=2；null 表示全部。
        assertThat(searchCalls).extracting(SearchCall::status)
                .containsExactly(1, 2, null);
    }

    @Test
    void statusName_mapsKnownCodesAndFallsBackToUnknown() {
        assertThat(AccessApplicationService.statusName(0)).isEqualTo("PENDING");
        assertThat(AccessApplicationService.statusName(1)).isEqualTo("APPROVED");
        assertThat(AccessApplicationService.statusName(2)).isEqualTo("REJECTED");
        assertThat(AccessApplicationService.statusName(99)).isEqualTo("UNKNOWN");
        assertThat(AccessApplicationService.statusName(null)).isNull();
    }

    @Test
    void page_rejectsUnknownStatusWithBadRequest() {
        assertThatThrownBy(() -> service.page("SUBMITTED", PageQuery.of(1, 20)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("非法申请状态");
                });
        assertThat(searchCalls).isEmpty();
    }

    @Test
    void detail_returnsOwnApplication() {
        applications.put(PENDING_NO, application(PENDING_APPLICATION_ID, PENDING_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        snapshots.put(PENDING_APPLICATION_ID, List.of(
                snapshot(PENDING_APPLICATION_ID, OPERATOR_ROLE_ID, "operator", "CRM运营")));

        ApplicationVO vo = service.detail(PENDING_NO);

        assertThat(vo.applicationNo()).isEqualTo(PENDING_NO);
        assertThat(vo.roleCount()).isEqualTo(1);
        assertThat(vo.roles()).extracting(RoleVO::roleName).containsExactly("CRM运营");
        assertThat(vo.approvalUrl()).isEqualTo(TEMPLATE.replace("{applicationNo}", PENDING_NO));
    }

    @Test
    void detail_returnsNotFoundForOtherUserOtherSystemOrMissing() {
        String otherUserNo = "RA-20261025-OTHER1";
        String otherSystemNo = "RA-20261025-OTHER2";
        applications.put(otherUserNo, application(101L, otherUserNo, CRM_ID, OTHER_USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        applications.put(otherSystemNo, application(102L, otherSystemNo, ERP_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));

        for (String applicationNo : List.of(otherUserNo, otherSystemNo, "RA-20261025-MISSING")) {
            assertThatThrownBy(() -> service.detail(applicationNo))
                    .as(applicationNo)
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.getStatus()).isEqualTo(404);
                        assertThat(exception.getMessage()).isEqualTo("申请单不存在");
                    });
        }
    }

    @Test
    void detail_approvedApplicationHasNoApprovalUrlAndUsesInstant() {
        SysAccessApplication approved = application(103L, APPROVED_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
        approved.setApprovedValidFrom(LocalDateTime.of(2026, 10, 25, 1, 0));
        approved.setApprovedValidUntil(LocalDateTime.of(2026, 10, 26, 1, 0));
        approved.setDecisionComment("通过");
        approved.setReviewedBy("approver");
        approved.setReviewedAt(LocalDateTime.of(2026, 10, 25, 2, 0));
        applications.put(APPROVED_NO, approved);

        ApplicationVO vo = service.detail(APPROVED_NO);

        assertThat(vo.status()).isEqualTo("APPROVED");
        assertThat(vo.approvalUrl()).isNull();
        assertThat(vo.approvedValidFrom()).isEqualTo(Instant.parse("2026-10-25T01:00:00Z"));
        assertThat(vo.approvedValidUntil()).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        assertThat(vo.reviewedAt()).isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
        assertThat(vo.decisionComment()).isEqualTo("通过");
        assertThat(vo.reviewedBy()).isEqualTo("approver");
    }

    @Test
    void create_persistsPendingApplicationWithGeneratedNumberAndSnapshots() {
        ApplicationVO vo = service.create(new CreateRequest(List.of(AUDITOR_ROLE_ID),
                "  季度末需要批量导出订单  "));

        assertThat(vo.id()).isNotNull();
        assertThat(vo.applicationNo()).matches("RA-\\d{8}-[A-Z0-9]{6}");
        assertThat(vo.systemId()).isEqualTo(CRM_ID);
        assertThat(vo.systemCode()).isEqualTo("crm");
        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.roleCount()).isEqualTo(1);
        assertThat(vo.roles()).extracting(RoleVO::roleCode).containsExactly("auditor");
        assertThat(vo.reason()).isEqualTo("季度末需要批量导出订单");
        assertThat(vo.approvedValidFrom()).isNull();
        assertThat(vo.approvedValidUntil()).isNull();
        assertThat(vo.approvalUrl()).isEqualTo(TEMPLATE.replace("{applicationNo}",
                vo.applicationNo()));

        assertThat(pendingCalls).singleElement().satisfies(call -> {
            assertThat(call.systemId()).isEqualTo(CRM_ID);
            assertThat(call.externalUserId()).isEqualTo(USER);
        });
        assertThat(roleLookupRoleIds).containsExactly(AUDITOR_ROLE_ID);
        assertThat(roleLookupSystemIds).containsExactly(CRM_ID);
        assertThat(savedApplicationNos).containsExactly(vo.applicationNo());
        SysAccessApplication saved = applications.get(vo.applicationNo());
        assertThat(saved.getSystemId()).isEqualTo(CRM_ID);
        assertThat(saved.getExternalUserId()).isEqualTo(USER);
        assertThat(saved.getStatus()).isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
        assertThat(saved.getReason()).isEqualTo("季度末需要批量导出订单");
        assertThat(snapshots.get(saved.getId())).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.getRoleId()).isEqualTo(AUDITOR_ROLE_ID);
            assertThat(snapshot.getRoleCode()).isEqualTo("auditor");
            assertThat(snapshot.getRoleName()).isEqualTo("审计员");
        });
    }

    @Test
    void create_deduplicatesRoleIdsAndAcceptsMultipleRoles() {
        ApplicationVO vo = service.create(new CreateRequest(
                List.of(AUDITOR_ROLE_ID, AUDITOR_ROLE_ID), "多角色申请"));

        assertThat(vo.roleCount()).isEqualTo(1);
        assertThat(vo.roles()).extracting(RoleVO::roleId).containsExactly(AUDITOR_ROLE_ID);
        assertThat(roleLookupRoleIds).containsExactly(AUDITOR_ROLE_ID);
    }

    @Test
    void create_rejectsInvalidRoleLists() {
        assertThatThrownBy(() -> service.create(new CreateRequest(List.of(), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("roleIds 不能为空");
                });
        assertThatThrownBy(() -> service.create(new CreateRequest(null, "原因")))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(400));

        List<Long> tooMany = new ArrayList<>();
        for (long index = 0; index < 21; index++) {
            tooMany.add(index + 1);
        }
        assertThatThrownBy(() -> service.create(new CreateRequest(tooMany, "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("一次最多申请 20 个角色");
                });
        assertThatThrownBy(() -> service.create(new CreateRequest(
                Arrays.asList(AUDITOR_ROLE_ID, null), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("roleIds 不能包含空值");
                });
        assertThat(savedApplicationNos).isEmpty();
        assertThat(pendingCalls).isEmpty();
    }

    @Test
    void create_rejectsBlankOrOversizedReason() {
        for (String reason : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> service.create(
                    new CreateRequest(List.of(AUDITOR_ROLE_ID), reason)))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(400));
        }
        assertThatThrownBy(() -> service.create(new CreateRequest(List.of(AUDITOR_ROLE_ID),
                "x".repeat(201))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("申请原因不能超过 200 字");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_rejectsRoleOutsideCurrentSystemOrMissing() {
        assertThatThrownBy(() -> service.create(new CreateRequest(List.of(999L), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("角色不属于当前系统");
                });
        assertThat(roleLookupSystemIds).containsExactly(CRM_ID);
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_rejectsDisabledRole() {
        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(INTERN_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("角色已停用，不能申请");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_rejectsAdminRole() {
        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(ADMIN_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("系统管理员角色不可自助申请");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_rejectsAlreadyOwnedRole() {
        ownedRoleIds.add(AUDITOR_ROLE_ID);

        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(AUDITOR_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("已拥有该角色，无需申请");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_rejectsWhenAnotherPendingApplicationExists() {
        SysAccessApplication pending = application(PENDING_APPLICATION_ID, PENDING_NO, CRM_ID,
                USER, RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
        applications.put(PENDING_NO, pending);

        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(AUDITOR_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("已有待审批申请，请等待审批完成");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_mapsUniqueConstraintViolationToConflict() {
        applicationSaveConflict = true;

        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(AUDITOR_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("已有待审批申请，请等待审批完成");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_givesUpAfterFiveDuplicateApplicationNumbers() {
        duplicateApplicationNo = true;

        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(AUDITOR_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(500);
                    assertThat(exception.getMessage()).isEqualTo("申请单号生成失败，请重试");
                });
        assertThat(existsApplicationNoCalls).hasSize(5);
        assertThat(savedApplicationNos).isEmpty();
    }

    @Test
    void create_requiresManagementContextAndAuthenticatedApplicant() {
        SecurityTestSupport.reset();
        SecurityTestSupport.authenticate(USER);
        SecurityTestSupport.requestWithSystemCode(null);

        assertThatThrownBy(() -> service.create(
                new CreateRequest(List.of(AUDITOR_ROLE_ID), "原因")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(403);
                    assertThat(exception.getMessage()).isEqualTo("X-System-Code header is required");
                });
        assertThat(savedApplicationNos).isEmpty();
    }

    private AccessApplicationService newService(RbacProperties properties) {
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService contextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        return new AccessApplicationService(applicationRepository, applicationRoleRepository,
                roleRepository, userRoleRepository, rolePermissionRepository, systemRepository,
                contextService, currentUserProvider, properties);
    }

    private static SysSystem system(Long id, String code, String name) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(name);
        system.setStatus(RbacConstants.STATUS_ENABLED);
        system.setIsPlatform(0);
        return system;
    }

    private static SysRole role(Long id, String code, String name, int status, int sort) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setStatus(status);
        role.setSort(sort);
        return role;
    }

    private static SysAccessApplication application(Long id, String applicationNo, Long systemId,
                                                    String externalUserId, int status) {
        SysAccessApplication application = new SysAccessApplication();
        application.setId(id);
        application.setApplicationNo(applicationNo);
        application.setSystemId(systemId);
        application.setExternalUserId(externalUserId);
        application.setStatus(status);
        return application;
    }

    private static SysAccessApplicationRole snapshot(Long applicationId, Long roleId,
                                                     String roleCode, String roleName) {
        SysAccessApplicationRole snapshot = new SysAccessApplicationRole();
        snapshot.setApplicationId(applicationId);
        snapshot.setRoleId(roleId);
        snapshot.setRoleCode(roleCode);
        snapshot.setRoleName(roleName);
        return snapshot;
    }

    private static AccessRolePermissionRow permissionRow(Long roleId, Long id, String code,
                                                         String name, int type) {
        AccessRolePermissionRow row = new AccessRolePermissionRow();
        row.setRoleId(roleId);
        row.setId(id);
        row.setCode(code);
        row.setName(name);
        row.setType(type);
        return row;
    }

    private record PermissionPreviewCall(Long systemId, List<Long> roleIds) {
    }

    private record RoleQueryCall(Long systemId, Integer status) {
    }

    private record SearchCall(Long systemId, String externalUserId, Integer status, long page, long size) {
    }

    private record PendingCall(Long systemId, String externalUserId) {
    }
}
