package com.telbank.rbac.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import com.telbank.rbac.domain.mapper.SysAccessApplicationMapper;
import com.telbank.rbac.domain.mapper.SysAccessApplicationRoleMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionResponse;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

/**
 * 业务用户临时权限申请的审批决策回调（外部审批平台 → 权限中心）。
 *
 * <p>按申请单号加行锁串行处理；只有 {@code PENDING} 申请单可决策。
 * {@code APPROVED} 在同一审批窗口重复回调时幂等返回，不重复授权；
 * 通过时逐角色复用 {@link ApprovalGrantService#grant(ApprovalGrantRequest)} 写
 * {@code sys_user_role}，任一角色失败则整体事务回滚、申请单保持 {@code PENDING}。
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

    public AccessDecisionService(SysAccessApplicationMapper applicationRepository,
                                 SysAccessApplicationRoleMapper applicationRoleRepository,
                                 SysRoleMapper roleRepository,
                                 SysUserRoleMapper userRoleRepository,
                                 SysSystemMapper systemRepository,
                                 ApprovalGrantService approvalGrantService) {
        this.applicationRepository = applicationRepository;
        this.applicationRoleRepository = applicationRoleRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.systemRepository = systemRepository;
        this.approvalGrantService = approvalGrantService;
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
            Window window = requireWindow(request.validFrom(), request.validUntil());
            if (isApproved(application) && sameWindow(application, window)) {
                return response(application, snapshotRoleIds(application.getId()), true);
            }
            if (!isPending(application)) {
                throw ApiException.conflict("申请单已审批，不能重复决策");
            }
            List<Long> grantedRoleIds = approve(application, window, request);
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

    private List<Long> approve(SysAccessApplication application, Window window,
                               DecisionRequest request) {
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
        }

        Set<Long> activeRoleIds = new HashSet<>(userRoleRepository.findActiveRoleIds(
                application.getSystemId(), application.getExternalUserId()));
        for (SysAccessApplicationRole snapshot : snapshots) {
            if (activeRoleIds.contains(snapshot.getRoleId())) {
                throw ApiException.conflict("申请人已拥有该角色，不能重复授权");
            }
        }

        List<Long> grantedRoleIds = new ArrayList<>(snapshots.size());
        for (SysAccessApplicationRole snapshot : snapshots) {
            Long roleId = snapshot.getRoleId();
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
        }

        application.setStatus(RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED);
        application.setApprovedValidFrom(TimeUtils.toUtcLocalDateTime(window.from()));
        application.setApprovedValidUntil(TimeUtils.toUtcLocalDateTime(window.until()));
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

    private static Window requireWindow(Instant from, Instant until) {
        if (from == null || until == null) {
            throw ApiException.badRequest("validFrom 和 validUntil 不能为空");
        }
        if (!until.isAfter(from)) {
            throw ApiException.badRequest("validUntil 必须晚于 validFrom");
        }
        if (!until.isAfter(Instant.now())) {
            throw ApiException.badRequest("validUntil 必须晚于当前时间");
        }
        return new Window(from, until);
    }

    private static boolean sameWindow(SysAccessApplication application, Window window) {
        return window.from().equals(TimeUtils.toInstant(application.getApprovedValidFrom()))
                && window.until().equals(TimeUtils.toInstant(application.getApprovedValidUntil()));
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

    private record Window(Instant from, Instant until) {
    }
}
