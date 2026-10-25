package com.telbank.rbac.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysAccessApplication;
import com.telbank.rbac.domain.entity.SysAccessApplicationRole;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysAccessApplicationMapper;
import com.telbank.rbac.domain.mapper.SysAccessApplicationRoleMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.service.RoleTimeWindowService.TimeWindow;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionResponse;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

/**
 * 业务用户临时权限申请的审批决策回调（外部审批平台 → 权限中心）。
 *
 * <p>按申请单号加行锁串行处理；只有 {@code PENDING} 申请单可决策。
 * {@code APPROVED} 重复回调幂等返回、窗口不回滚；通过时逐角色按绑定时间模板计算窗口，
 * 复用 {@link ApprovalGrantService#grant(ApprovalGrantRequest)} 写 {@code sys_user_role}，
 * 并把每个角色的模板与窗口回写快照；主单保存所有角色窗口的包络（最早开始 / 最晚结束）。
 * 任一角色失败则整体事务回滚、申请单保持 {@code PENDING}。
 * {@code REJECTED} 只回写申请状态与审批字段，不写授权。</p>
 */
@Service
public class AccessDecisionService {

    private static final String DECISION_APPROVED = "APPROVED";
    private static final String DECISION_REJECTED = "REJECTED";
    private static final String GRANT_TYPE_ROLE = "role";
    private static final String ADMIN_ROLE_CODE = "admin";
    private static final String DEFAULT_REVIEWER = "external-approval";
    private static final int MAX_GRANT_REASON_LENGTH = 255;
    private static final int MAX_DECISION_COMMENT_LENGTH = 255;
    private static final int MAX_REVIEWER_LENGTH = 128;

    private final SysAccessApplicationMapper applicationRepository;
    private final SysAccessApplicationRoleMapper applicationRoleRepository;
    private final SysRoleMapper roleRepository;
    private final SysUserRoleMapper userRoleRepository;
    private final SysSystemMapper systemRepository;
    private final ApprovalGrantService approvalGrantService;
    private final RoleTimeWindowService roleTimeWindowService;

    public AccessDecisionService(SysAccessApplicationMapper applicationRepository,
                                 SysAccessApplicationRoleMapper applicationRoleRepository,
                                 SysRoleMapper roleRepository,
                                 SysUserRoleMapper userRoleRepository,
                                 SysSystemMapper systemRepository,
                                 ApprovalGrantService approvalGrantService,
                                 RoleTimeWindowService roleTimeWindowService) {
        this.applicationRepository = applicationRepository;
        this.applicationRoleRepository = applicationRoleRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.systemRepository = systemRepository;
        this.approvalGrantService = approvalGrantService;
        this.roleTimeWindowService = roleTimeWindowService;
    }

    /**
     * 处理一次审批决策：加行锁读取申请单 → 校验状态/窗口 → 授权（仅通过）→ 回写申请单。
     *
     * @return 决策结果；{@code idempotent=true} 表示该申请单此前已按相同结果处理
     */
    @Transactional
    public DecisionResponse decide(DecisionRequest request) {
        String decision = normalizeDecision(request == null ? null : request.decision());
        SysAccessApplication application = lockApplication(
                request == null ? null : request.applicationNo());

        if (DECISION_APPROVED.equals(decision)) {
            if (isApproved(application)) {
                return response(application, snapshotRoleIds(application.getId()), true);
            }
            if (!isPending(application)) {
                throw ApiException.conflict("申请单已审批，不能重复决策");
            }
            List<Long> grantedRoleIds = approve(application, request);
            return response(application, grantedRoleIds, false);
        }

        if (isRejected(application)) {
            return response(application, List.of(), true);
        }
        if (!isPending(application)) {
            throw ApiException.conflict("申请单已审批，不能重复决策");
        }
        reject(application, request);
        return response(application, List.of(), false);
    }

    private SysAccessApplication lockApplication(String applicationNo) {
        if (applicationNo == null || applicationNo.isBlank()) {
            throw ApiException.notFound("申请单不存在");
        }
        return applicationRepository.findByApplicationNoForUpdate(applicationNo.trim())
                .orElseThrow(() -> ApiException.notFound("申请单不存在"));
    }

    private List<Long> approve(SysAccessApplication application, DecisionRequest request) {
        SysSystem system = systemRepository.findById(application.getSystemId())
                .orElseThrow(() -> ApiException.conflict("申请单所属系统不存在或已停用"));
        if (!Integer.valueOf(RbacConstants.STATUS_ENABLED).equals(system.getStatus())) {
            throw ApiException.conflict("申请单所属系统不存在或已停用");
        }

        List<SysAccessApplicationRole> snapshots = applicationRoleRepository
                .findByApplicationIdOrderByIdAsc(application.getId());
        if (snapshots.isEmpty()) {
            throw ApiException.conflict("申请单缺少角色信息，无法审批");
        }
        Map<Long, SysRole> roleById = new LinkedHashMap<>();
        for (SysAccessApplicationRole snapshot : snapshots) {
            SysRole role = roleRepository
                    .findByIdAndSystemId(snapshot.getRoleId(), application.getSystemId())
                    .orElseThrow(() -> ApiException.conflict("申请角色不存在或已停用"));
            if (!Integer.valueOf(RbacConstants.STATUS_ENABLED).equals(role.getStatus())) {
                throw ApiException.conflict("申请角色不存在或已停用");
            }
            if (ADMIN_ROLE_CODE.equals(role.getCode())) {
                throw ApiException.conflict("系统管理员角色不可审批授权");
            }
            roleById.put(role.getId(), role);
        }

        Set<Long> activeRoleIds = new HashSet<>(userRoleRepository.findActiveRoleIds(
                application.getSystemId(), application.getExternalUserId()));
        for (SysAccessApplicationRole snapshot : snapshots) {
            if (activeRoleIds.contains(snapshot.getRoleId())) {
                throw ApiException.conflict("申请人已拥有该角色，不能重复授权");
            }
        }

        List<Long> grantedRoleIds = new ArrayList<>(snapshots.size());
        Instant envelopeFrom = null;
        Instant envelopeUntil = null;
        for (SysAccessApplicationRole snapshot : snapshots) {
            SysRole role = roleById.get(snapshot.getRoleId());
            if (role.getTimeTemplateId() == null) {
                throw ApiException.conflict("申请角色未绑定时间模板，无法审批");
            }
            SysTimeTemplate template = roleTimeWindowService.requireEnabled(role.getTimeTemplateId());
            TimeWindow window = roleTimeWindowService.compute(template);
            Long roleId = role.getId();
            ApprovalGrantResponse granted = approvalGrantService.grant(new ApprovalGrantRequest(
                    system.getCode(), application.getExternalUserId(), GRANT_TYPE_ROLE, roleId,
                    null, window.from(), window.until(),
                    application.getApplicationNo() + ":" + roleId,
                    truncate("申请单 " + application.getApplicationNo() + "："
                            + application.getReason(), MAX_GRANT_REASON_LENGTH)));
            if (granted == null || !granted.success()) {
                throw ApiException.conflict("授权失败，请重试");
            }
            grantedRoleIds.add(roleId);
            // 以落库后的实际用户-角色窗口为准，避免审批时模板二次计算在边界时刻产生偏差
            SysUserRole grantedRelation = userRoleRepository
                    .findBySystemIdAndExternalUserIdAndRoleId(
                            application.getSystemId(), application.getExternalUserId(), roleId)
                    .orElseThrow(() -> ApiException.conflict("授权关系写入失败，请重试"));
            Instant grantedFrom = TimeUtils.toInstant(grantedRelation.getValidFrom());
            Instant grantedUntil = TimeUtils.toInstant(grantedRelation.getValidUntil());
            if (grantedFrom == null || grantedUntil == null) {
                throw ApiException.conflict("授权窗口写入失败，请重试");
            }
            snapshot.setTimeTemplateId(template.getId());
            snapshot.setTimeTemplateName(template.getName());
            snapshot.setApprovedValidFrom(TimeUtils.toUtcLocalDateTime(grantedFrom));
            snapshot.setApprovedValidUntil(TimeUtils.toUtcLocalDateTime(grantedUntil));
            applicationRoleRepository.save(snapshot);
            envelopeFrom = envelopeFrom == null || grantedFrom.isBefore(envelopeFrom)
                    ? grantedFrom : envelopeFrom;
            envelopeUntil = envelopeUntil == null || grantedUntil.isAfter(envelopeUntil)
                    ? grantedUntil : envelopeUntil;
        }

        application.setStatus(RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
        application.setApprovedValidFrom(TimeUtils.toUtcLocalDateTime(envelopeFrom));
        application.setApprovedValidUntil(TimeUtils.toUtcLocalDateTime(envelopeUntil));
        application.setDecisionComment(
                truncate(request.decisionComment(), MAX_DECISION_COMMENT_LENGTH));
        application.setReviewedBy(reviewer(request.reviewer()));
        application.setReviewedAt(TimeUtils.toUtcLocalDateTime(Instant.now()));
        applicationRepository.updateById(application);
        return grantedRoleIds;
    }

    private void reject(SysAccessApplication application, DecisionRequest request) {
        application.setStatus(RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED);
        application.setDecisionComment(
                truncate(request.decisionComment(), MAX_DECISION_COMMENT_LENGTH));
        application.setReviewedBy(reviewer(request.reviewer()));
        application.setReviewedAt(TimeUtils.toUtcLocalDateTime(Instant.now()));
        applicationRepository.updateById(application);
    }

    private static String normalizeDecision(String decision) {
        if (decision == null) {
            throw ApiException.badRequest("decision 只能是 APPROVED 或 REJECTED");
        }
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if (!DECISION_APPROVED.equals(normalized) && !DECISION_REJECTED.equals(normalized)) {
            throw ApiException.badRequest("decision 只能是 APPROVED 或 REJECTED");
        }
        return normalized;
    }

    private List<Long> snapshotRoleIds(Long applicationId) {
        return applicationRoleRepository.findByApplicationIdOrderByIdAsc(applicationId).stream()
                .map(SysAccessApplicationRole::getRoleId)
                .toList();
    }

    private static boolean isPending(SysAccessApplication application) {
        return Integer.valueOf(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING)
                .equals(application.getStatus());
    }

    private static boolean isApproved(SysAccessApplication application) {
        return Integer.valueOf(RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED)
                .equals(application.getStatus());
    }

    private static boolean isRejected(SysAccessApplication application) {
        return Integer.valueOf(RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED)
                .equals(application.getStatus());
    }

    private static String reviewer(String reviewer) {
        String normalized = reviewer == null ? null : reviewer.trim();
        return normalized == null || normalized.isEmpty()
                ? DEFAULT_REVIEWER
                : truncate(normalized, MAX_REVIEWER_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static DecisionResponse response(SysAccessApplication application,
                                             List<Long> grantedRoleIds, boolean idempotent) {
        return new DecisionResponse(application.getApplicationNo(),
                AccessApplicationService.statusName(application.getStatus()), idempotent,
                grantedRoleIds,
                TimeUtils.toInstant(application.getApprovedValidFrom()),
                TimeUtils.toInstant(application.getApprovedValidUntil()));
    }
}
