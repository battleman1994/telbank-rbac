package com.telbank.rbac.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysAccessApplication;
import com.telbank.rbac.domain.entity.SysAccessApplicationRole;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysAccessApplicationMapper;
import com.telbank.rbac.domain.mapper.SysAccessApplicationRoleMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionResponse;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批决策回调：行锁读取、PENDING 校验、窗口校验、逐角色授权、幂等与冲突语义。
 */
class AccessDecisionServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long DISABLED_SYSTEM_ID = 2L;
    private static final Long ADMIN_ROLE_ID = 10L;
    private static final Long OPERATOR_ROLE_ID = 11L;
    private static final Long AUDITOR_ROLE_ID = 12L;
    private static final Long INTERN_ROLE_ID = 13L;
    private static final Long APPLICATION_ID = 100L;
    private static final String APP_NO = "RA-20261025-A1B2C3";
    private static final String USER = "u-crm-001";
    private static final String REASON = "季度末批量导出订单";
    private static final Instant FROM = Instant.parse("2026-10-26T01:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-10-27T01:00:00Z");

    private final Map<String, SysAccessApplication> applications = new ConcurrentHashMap<>();
    private final Map<Long, SysRole> roles = new ConcurrentHashMap<>();
    private final Map<Long, SysSystem> systems = new ConcurrentHashMap<>();
    private final List<SysAccessApplicationRole> snapshots = new ArrayList<>();
    private final List<Long> activeRoleIds = new ArrayList<>();
    private final List<ApprovalGrantRequest> grantCalls = new ArrayList<>();
    private final List<SysAccessApplication> updatedApplications = new ArrayList<>();
    private final List<String> lockCalls = new ArrayList<>();
    private final Set<Long> failingGrantRoleIds = new HashSet<>();
    private final AtomicLong grantIds = new AtomicLong(500L);

    private SysAccessApplicationMapper applicationRepository;
    private SysAccessApplicationRoleMapper applicationRoleRepository;
    private SysRoleMapper roleRepository;
    private SysUserRoleMapper userRoleRepository;
    private SysSystemMapper systemRepository;
    private ApprovalGrantService approvalGrantService;
    private AccessDecisionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        applications.clear();
        roles.clear();
        systems.clear();
        snapshots.clear();
        activeRoleIds.clear();
        grantCalls.clear();
        updatedApplications.clear();
        lockCalls.clear();
        failingGrantRoleIds.clear();
        grantIds.set(500L);

        systems.put(CRM_ID, system(CRM_ID, "crm", "CRM系统", RbacConstants.STATUS_ENABLED));
        systems.put(DISABLED_SYSTEM_ID,
                system(DISABLED_SYSTEM_ID, "erp", "ERP系统", 0));
        roles.put(ADMIN_ROLE_ID, role(ADMIN_ROLE_ID, "admin", "管理员",
                RbacConstants.STATUS_ENABLED));
        roles.put(OPERATOR_ROLE_ID, role(OPERATOR_ROLE_ID, "operator", "CRM运营",
                RbacConstants.STATUS_ENABLED));
        roles.put(AUDITOR_ROLE_ID, role(AUDITOR_ROLE_ID, "auditor", "审计员",
                RbacConstants.STATUS_ENABLED));
        roles.put(INTERN_ROLE_ID, role(INTERN_ROLE_ID, "intern", "实习生", 0));

        applicationRepository = TestProxies.proxy(SysAccessApplicationMapper.class,
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByApplicationNoForUpdate": {
                            lockCalls.add((String) args[0]);
                            return Optional.ofNullable(applications.get((String) args[0]));
                        }
                        case "updateById": {
                            SysAccessApplication application = (SysAccessApplication) args[0];
                            updatedApplications.add(application);
                            applications.put(application.getApplicationNo(), application);
                            return 1;
                        }
                        default:
                            return TestProxies.defaultValue(method.getReturnType());
                    }
                });
        applicationRoleRepository = TestProxies.proxy(SysAccessApplicationRoleMapper.class,
                (proxy, method, args) -> {
                    if ("findByApplicationIdOrderByIdAsc".equals(method.getName())) {
                        Long applicationId = (Long) args[0];
                        return snapshots.stream()
                                .filter(snapshot -> snapshot.getApplicationId().equals(applicationId))
                                .toList();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        roleRepository = TestProxies.proxy(SysRoleMapper.class, (proxy, method, args) -> {
            if ("findByIdAndSystemId".equals(method.getName())) {
                Long roleId = (Long) args[0];
                Long systemId = (Long) args[1];
                if (!CRM_ID.equals(systemId)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(roles.get(roleId));
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
        userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class, (proxy, method, args) -> {
            if ("findActiveRoleIds".equals(method.getName())) {
                return List.copyOf(activeRoleIds);
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
        systemRepository = TestProxies.proxy(SysSystemMapper.class, (proxy, method, args) -> {
            if ("findById".equals(method.getName())) {
                return Optional.ofNullable(systems.get((Long) args[0]));
            }
            return TestProxies.defaultValue(method.getReturnType());
        });
        // 授权服务只验证调用参数与返回契约，落库细节由 ApprovalGrantServiceTest 覆盖。
        approvalGrantService = new ApprovalGrantService(null, null, null, null, null) {
            @Override
            public ApprovalGrantResponse grant(ApprovalGrantRequest request) {
                grantCalls.add(request);
                if (failingGrantRoleIds.contains(request.roleId())) {
                    throw ApiException.conflict("grant failed");
                }
                return new ApprovalGrantResponse(true, grantIds.getAndIncrement(), false);
            }
        };

        service = new AccessDecisionService(applicationRepository, applicationRoleRepository,
                roleRepository, userRoleRepository, systemRepository, approvalGrantService);
    }

    @AfterEach
    void tearDown() {
        // 无安全上下文，回调依赖 AppKey 验签，不依赖 Spring Security。
    }

    @Test
    void approvePendingApplicationGrantsAllRolesAndWritesDecision() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");
        givenSnapshot(AUDITOR_ROLE_ID, "auditor", "审计员");

        DecisionResponse response = service.decide(new DecisionRequest(APP_NO, " approved ",
                FROM, UNTIL, " 审批人 ", "同意"));

        assertThat(response.applicationNo()).isEqualTo(APP_NO);
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.idempotent()).isFalse();
        assertThat(response.grantedRoleIds()).containsExactly(OPERATOR_ROLE_ID, AUDITOR_ROLE_ID);
        assertThat(response.approvedValidFrom()).isEqualTo(FROM);
        assertThat(response.approvedValidUntil()).isEqualTo(UNTIL);

        assertThat(lockCalls).containsExactly(APP_NO);
        assertThat(grantCalls).hasSize(2);
        assertThat(grantCalls).extracting(ApprovalGrantRequest::roleId)
                .containsExactly(OPERATOR_ROLE_ID, AUDITOR_ROLE_ID);
        assertThat(grantCalls).allSatisfy(grant -> {
            assertThat(grant.systemCode()).isEqualTo("crm");
            assertThat(grant.externalUserId()).isEqualTo(USER);
            assertThat(grant.grantType()).isEqualTo("role");
            assertThat(grant.permissionCodes()).isNull();
            assertThat(grant.validFrom()).isEqualTo(FROM);
            assertThat(grant.validUntil()).isEqualTo(UNTIL);
            assertThat(grant.reason()).isEqualTo("申请单 " + APP_NO + "：" + REASON);
        });
        assertThat(grantCalls).extracting(ApprovalGrantRequest::approvalId)
                .containsExactly(APP_NO + ":" + OPERATOR_ROLE_ID, APP_NO + ":" + AUDITOR_ROLE_ID);

        assertThat(updatedApplications).singleElement().satisfies(application -> {
            assertThat(application.getStatus()).isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
            assertThat(application.getApprovedValidFrom())
                    .isEqualTo(TimeUtils.toUtcLocalDateTime(FROM));
            assertThat(application.getApprovedValidUntil())
                    .isEqualTo(TimeUtils.toUtcLocalDateTime(UNTIL));
            assertThat(application.getDecisionComment()).isEqualTo("同意");
            assertThat(application.getReviewedBy()).isEqualTo("审批人");
            assertThat(application.getReviewedAt()).isNotNull();
        });
    }

    @Test
    void approveIsIdempotentForSameWindowWithoutRegranting() {
        SysAccessApplication approved = application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
        approved.setApprovedValidFrom(TimeUtils.toUtcLocalDateTime(FROM));
        approved.setApprovedValidUntil(TimeUtils.toUtcLocalDateTime(UNTIL));
        givenApplication(approved);
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");

        DecisionResponse response = service.decide(new DecisionRequest(APP_NO, "APPROVED",
                FROM, UNTIL, "审批人", "同意"));

        assertThat(response.idempotent()).isTrue();
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.grantedRoleIds()).containsExactly(OPERATOR_ROLE_ID);
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void approveConflictsWhenAlreadyApprovedWithDifferentWindow() {
        SysAccessApplication approved = application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
        approved.setApprovedValidFrom(TimeUtils.toUtcLocalDateTime(FROM));
        approved.setApprovedValidUntil(TimeUtils.toUtcLocalDateTime(UNTIL));
        givenApplication(approved);

        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "APPROVED",
                FROM.plusSeconds(3600), UNTIL.plusSeconds(3600), null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请单已审批，不能重复决策");
                });
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void approveConflictsWhenApplicationAlreadyRejected() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED));

        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "APPROVED",
                FROM, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请单已审批，不能重复决策");
                });
        assertThat(grantCalls).isEmpty();
    }

    @Test
    void rejectsUnsupportedOrMissingDecisionWithBadRequest() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));

        for (String decision : List.of("MAYBE", "", "  ")) {
            assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, decision,
                    FROM, UNTIL, null, null)))
                    .as(decision)
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.getStatus()).isEqualTo(400);
                        assertThat(exception.getMessage())
                                .isEqualTo("decision 只能是 APPROVED 或 REJECTED");
                    });
        }
        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, null,
                FROM, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(400));
        assertThat(lockCalls).isEmpty();
    }

    @Test
    void returnsNotFoundForUnknownOrBlankApplicationNo() {
        assertThatThrownBy(() -> service.decide(new DecisionRequest("RA-20261025-MISSING",
                "APPROVED", FROM, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(404);
                    assertThat(exception.getMessage()).isEqualTo("申请单不存在");
                });
        assertThatThrownBy(() -> service.decide(new DecisionRequest("  ", "APPROVED",
                FROM, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(404));
        assertThat(lockCalls).containsExactly("RA-20261025-MISSING");
    }

    @Test
    void approveRequiresAValidFutureWindow() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");

        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "APPROVED",
                null, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("validFrom 和 validUntil 不能为空");
                });
        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "APPROVED",
                UNTIL, UNTIL, null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("validUntil 必须晚于 validFrom");
                });
        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "APPROVED",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600),
                null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("validUntil 必须晚于当前时间");
                });
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void approveRejectsMissingOrDisabledSystem() {
        givenApplication(application(APPLICATION_ID, APP_NO, 999L, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请单所属系统不存在或已停用");
                });

        applications.clear();
        givenApplication(application(APPLICATION_ID, APP_NO, DISABLED_SYSTEM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(409));
        assertThat(grantCalls).isEmpty();
    }

    @Test
    void approveRejectsApplicationWithoutRoleSnapshot() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));

        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请单缺少角色信息，无法审批");
                });
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void approveRejectsRolesThatAreNoLongerApplicable() {
        assertRoleRejectedBy(null, "申请角色不存在或已停用");
        assertRoleRejectedBy(INTERN_ROLE_ID, "申请角色不存在或已停用");
        assertRoleRejectedBy(ADMIN_ROLE_ID, "系统管理员角色不可审批授权");
    }

    private void assertRoleRejectedBy(Long roleId, String expectedMessage) {
        applications.clear();
        snapshots.clear();
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        if (roleId == null) {
            givenSnapshot(999L, "ghost", "已删除角色");
        } else {
            SysRole role = roles.get(roleId);
            givenSnapshot(roleId, role.getCode(), role.getName());
        }

        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .as(String.valueOf(roleId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                });
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void approveRejectsWhenApplicantAlreadyHoldsAnAppliedRole() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");
        activeRoleIds.add(OPERATOR_ROLE_ID);

        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请人已拥有该角色，不能重复授权");
                });
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).isEmpty();
        assertThat(applications.get(APP_NO).getStatus())
                .isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
    }

    @Test
    void approveKeepsApplicationPendingWhenAnyGrantFails() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");
        givenSnapshot(AUDITOR_ROLE_ID, "auditor", "审计员");
        failingGrantRoleIds.add(AUDITOR_ROLE_ID);

        assertThatThrownBy(() -> service.decide(approvedRequest()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("grant failed");
                });
        assertThat(grantCalls).hasSize(2);
        assertThat(updatedApplications).isEmpty();
        assertThat(applications.get(APP_NO).getStatus())
                .isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
    }

    @Test
    void rejectPendingApplicationOnlyWritesReviewFields() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_PENDING));
        givenSnapshot(OPERATOR_ROLE_ID, "operator", "CRM运营");

        DecisionResponse response = service.decide(new DecisionRequest(APP_NO, "rejected",
                null, null, "  ", "材料不完整"));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.idempotent()).isFalse();
        assertThat(response.grantedRoleIds()).isEmpty();
        assertThat(response.approvedValidFrom()).isNull();
        assertThat(response.approvedValidUntil()).isNull();
        assertThat(grantCalls).isEmpty();
        assertThat(updatedApplications).singleElement().satisfies(application -> {
            assertThat(application.getStatus()).isEqualTo(RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED);
            assertThat(application.getDecisionComment()).isEqualTo("材料不完整");
            assertThat(application.getReviewedBy()).isEqualTo("external-approval");
            assertThat(application.getReviewedAt()).isNotNull();
            assertThat(application.getApprovedValidFrom()).isNull();
            assertThat(application.getApprovedValidUntil()).isNull();
        });
    }

    @Test
    void rejectIsIdempotentWhenAlreadyRejected() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED));

        DecisionResponse response = service.decide(new DecisionRequest(APP_NO, "REJECTED",
                null, null, null, null));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.idempotent()).isTrue();
        assertThat(response.grantedRoleIds()).isEmpty();
        assertThat(updatedApplications).isEmpty();
    }

    @Test
    void rejectConflictsWhenApplicationAlreadyApproved() {
        givenApplication(application(APPLICATION_ID, APP_NO, CRM_ID, USER,
                RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED));

        assertThatThrownBy(() -> service.decide(new DecisionRequest(APP_NO, "REJECTED",
                null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("申请单已审批，不能重复决策");
                });
        assertThat(updatedApplications).isEmpty();
    }

    private DecisionRequest approvedRequest() {
        return new DecisionRequest(APP_NO, "APPROVED", FROM, UNTIL, "审批人", "同意");
    }

    private void givenApplication(SysAccessApplication application) {
        applications.put(application.getApplicationNo(), application);
    }

    private void givenSnapshot(Long roleId, String roleCode, String roleName) {
        SysAccessApplicationRole snapshot = new SysAccessApplicationRole();
        snapshot.setApplicationId(APPLICATION_ID);
        snapshot.setRoleId(roleId);
        snapshot.setRoleCode(roleCode);
        snapshot.setRoleName(roleName);
        snapshots.add(snapshot);
    }

    private static SysAccessApplication application(Long id, String applicationNo, Long systemId,
                                                     String externalUserId, int status) {
        SysAccessApplication application = new SysAccessApplication();
        application.setId(id);
        application.setApplicationNo(applicationNo);
        application.setSystemId(systemId);
        application.setExternalUserId(externalUserId);
        application.setStatus(status);
        application.setReason(REASON);
        return application;
    }

    private static SysSystem system(Long id, String code, String name, int status) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(name);
        system.setStatus(status);
        system.setIsPlatform(0);
        return system;
    }

    private static SysRole role(Long id, String code, String name, int status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setStatus(status);
        return role;
    }
}
