package com.telbank.rbac.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批回调授权（契约 §5）：approvalId 幂等、grantType 约束、permissionCodes 审计、
 * 同关系更新原行（契约 §9.1）。
 */
class ApprovalGrantServiceTest {

    private static final String SYSTEM_CODE = "crm";
    private static final Long TEMPLATE_ID = 9L;

    private final Map<String, SysUserRole> byApprovalId = new LinkedHashMap<>();
    private final Map<String, SysUserRole> byRelation = new LinkedHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(100);
    private final List<String> published = new ArrayList<>();
    private final List<SysUserRole> saved = new ArrayList<>();

    private ApprovalGrantService service;
    private SysRole role;
    private SysTimeTemplateMapper templateRepository;

    @BeforeEach
    void setUp() {
        SysSystem crm = new SysSystem();
        crm.setId(1L);
        crm.setCode(SYSTEM_CODE);
        crm.setStatus(1);
        SysSystem erp = new SysSystem();
        erp.setId(2L);
        erp.setCode("erp");
        erp.setStatus(1);
        SysRole operatorRole = new SysRole();
        operatorRole.setId(5L);
        operatorRole.setSystemId(1L);
        operatorRole.setCode("operator");
        role = operatorRole;

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        if (SYSTEM_CODE.equals(args[0])) {
                            return Optional.of(crm);
                        }
                        if ("erp".equals(args[0])) {
                            return Optional.of(erp);
                        }
                        return Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        SysRoleMapper roleRepository = TestProxies.proxy(SysRoleMapper.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Long.valueOf(5L).equals(args[0]) ? Optional.of(role) : Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findFirstBySourceApprovalIdOrderByIdAsc":
                            return Optional.ofNullable(byApprovalId.get((String) args[0]));
                        case "findBySystemIdAndExternalUserIdAndRoleId":
                            return Optional.ofNullable(byRelation.get(
                                    relationKey((Long) args[0], (String) args[1], (Long) args[2])));
                        case "save": {
                            SysUserRole relation = (SysUserRole) args[0];
                            if (relation.getId() == null) {
                                relation.setId(idSequence.incrementAndGet());
                            }
                            if (relation.getSourceApprovalId() != null) {
                                byApprovalId.put(relation.getSourceApprovalId(), relation);
                            }
                            byRelation.put(relationKey(relation.getSystemId(), relation.getExternalUserId(),
                                    relation.getRoleId()), relation);
                            saved.add(relation);
                            return relation;
                        }
                        default:
                            return TestProxies.defaultValue(method.getReturnType());
                    }
                });

        SysRolePermissionMapper rolePermissionRepository = TestProxies.proxy(
                SysRolePermissionMapper.class, (proxy, method, args) -> {
                    if ("findGrantedCodesByRoleId".equals(method.getName())) {
                        return List.of("order:delete", "order:list");
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        templateRepository = TestProxies.proxy(SysTimeTemplateMapper.class,
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())
                            && TEMPLATE_ID.equals(args[0])) {
                        return Optional.of(template());
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        ChangeNotifyService changeNotifyService = new ChangeNotifyService(null, null, null, null, null) {
            @Override
            public void publishAfterCommit(Long systemId, String changeType) {
                published.add(systemId + ":" + changeType);
            }
        };

        service = new ApprovalGrantService(systemRepository, roleRepository, userRoleRepository,
                rolePermissionRepository, changeNotifyService,
                new RoleTimeWindowService(templateRepository));
    }

    @Test
    void grantsRoleWithValidityAuditFieldsAndPublishesChange() {
        ApprovalGrantResponse response = service.grant(request("APV-20261024-001", List.of("order:delete")));

        assertThat(response.success()).isTrue();
        assertThat(response.idempotent()).isFalse();
        assertThat(response.grantId()).isEqualTo(101L);
        assertThat(published).containsExactly("1:" + RbacConstants.CHANGE_TYPE_PERMISSION);

        SysUserRole relation = saved.get(0);
        assertThat(relation.getSystemId()).isEqualTo(1L);
        assertThat(relation.getExternalUserId()).isEqualTo("13800138000");
        assertThat(relation.getRoleId()).isEqualTo(5L);
        assertThat(relation.getSourceApprovalId()).isEqualTo("APV-20261024-001");
        assertThat(relation.getGrantReason()).isEqualTo("季度末批量清理订单");
        assertThat(relation.getValidFrom()).isEqualTo(java.time.LocalDateTime.parse("2026-10-24T00:00:00"));
        assertThat(relation.getValidUntil()).isEqualTo(java.time.LocalDateTime.parse("2026-10-31T23:59:59"));
        assertThat(relation.getStatus()).isEqualTo(1);
        assertThat(relation.getAutoRevoke()).isEqualTo(1);
    }

    @Test
    void repeatedApprovalIdReturnsIdempotentResultWithoutSecondWrite() {
        ApprovalGrantRequest request = request("APV-20261024-002", List.of("order:list"));

        ApprovalGrantResponse first = service.grant(request);
        ApprovalGrantResponse second = service.grant(request);

        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(second.grantId()).isEqualTo(first.grantId());
        assertThat(second.success()).isTrue();
        assertThat(saved).hasSize(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void existingRelationIsUpdatedInPlaceInsteadOfInserted() {
        SysUserRole existing = new SysUserRole();
        existing.setId(50L);
        existing.setSystemId(1L);
        existing.setExternalUserId("13800138000");
        existing.setRoleId(5L);
        existing.setStatus(0);
        byRelation.put(relationKey(1L, "13800138000", 5L), existing);

        ApprovalGrantResponse response = service.grant(request("APV-20261024-003", List.of()));

        assertThat(response.grantId()).isEqualTo(50L);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isEqualTo(50L);
        assertThat(saved.get(0).getStatus()).isEqualTo(1);
        assertThat(saved.get(0).getSourceApprovalId()).isEqualTo("APV-20261024-003");
    }

    @Test
    void grantTypePermissionIsRejectedWithReason() {
        ApprovalGrantRequest request = new ApprovalGrantRequest(SYSTEM_CODE, "13800138000", "permission",
                5L, null, null, null, "APV-PERM-1", "reason");

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("user_permission");
                });
    }

    @Test
    void unsupportedGrantTypeIsRejected() {
        ApprovalGrantRequest request = new ApprovalGrantRequest(SYSTEM_CODE, "13800138000", "role2",
                5L, null, null, null, "APV-BAD-TYPE", "reason");
        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(400));
    }

    @Test
    void permissionCodesNotGrantedToRoleAreRejected() {
        ApprovalGrantRequest request = request("APV-20261024-004", List.of("order:delete", "order:approve"));

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("order:approve");
                });
        assertThat(saved).isEmpty();
    }

    @Test
    void roleWithTemplateUsesTemplateWindowAndIgnoresRequestWindow() {
        role.setTimeTemplateId(TEMPLATE_ID);
        SysTimeTemplate template = template();

        ApprovalGrantResponse response = service.grant(request("APV-20261024-T01",
                List.of("order:list")));

        assertThat(response.success()).isTrue();
        RoleTimeWindowService.TimeWindow expected =
                new RoleTimeWindowService(templateRepository).compute(template);
        SysUserRole relation = saved.get(0);
        assertThat(relation.getValidFrom())
                .isEqualTo(TimeUtils.toUtcLocalDateTime(expected.from()));
        assertThat(relation.getValidUntil())
                .isEqualTo(TimeUtils.toUtcLocalDateTime(expected.until()));
        assertThat(relation.getValidFrom()).isNotEqualTo(
                TimeUtils.toUtcLocalDateTime(Instant.parse("2026-10-24T00:00:00Z")));
    }

    @Test
    void invalidValidityWindowIsRejected() {
        ApprovalGrantRequest request = new ApprovalGrantRequest(SYSTEM_CODE, "13800138000", "role", 5L,
                List.of(), Instant.parse("2026-10-31T00:00:00Z"), Instant.parse("2026-10-24T00:00:00Z"),
                "APV-20261024-005", "reason");

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("validUntil");
                });
    }

    @Test
    void roleOfAnotherSystemIsRejected() {
        // role 5 属于 crm，这里传入 erp 的 systemCode
        ApprovalGrantRequest request = new ApprovalGrantRequest("erp", "13800138000", "role", 5L,
                List.of(), null, null, "APV-20261024-006", "reason");

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("does not belong");
                });
    }

    @Test
    void unknownRoleIsRejected() {
        ApprovalGrantRequest request = new ApprovalGrantRequest(SYSTEM_CODE, "13800138000", "role", 999L,
                List.of(), null, null, "APV-20261024-007", "reason");
        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(400));
    }

    private static SysTimeTemplate template() {
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

    private static ApprovalGrantRequest request(String approvalId, List<String> permissionCodes) {
        return new ApprovalGrantRequest(SYSTEM_CODE, "13800138000", "role", 5L, permissionCodes,
                Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-31T23:59:59Z"),
                approvalId, "季度末批量清理订单");
    }

    private static String relationKey(Long systemId, String externalUserId, Long roleId) {
        return systemId + "|" + externalUserId + "|" + roleId;
    }
}
