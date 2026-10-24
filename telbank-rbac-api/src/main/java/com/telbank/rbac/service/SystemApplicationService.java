package com.telbank.rbac.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysSystemApplication;
import com.telbank.rbac.domain.mapper.SysSystemApplicationMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportRequest;
import com.telbank.rbac.web.dto.OnboardingDtos.SystemReportResponse;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveResponse;
import com.telbank.rbac.web.dto.SystemApplicationDtos.RejectRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.SystemApplicationVO;
import com.telbank.rbac.web.dto.SystemDtos.SystemCreateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemVO;

/**
 * 系统接入申请（A16）：SDK 上报（onboarding AppKey）+ 平台超管审核。
 *
 * <p>上报只维护 {@code sys_system_application} 的单条最新状态（system_code 唯一），不直接建系统；
 * approve 复用 {@link SystemService#create} 创建系统与默认 admin 角色/通配权限/管理员授权，
 * 并返回本次分配的 appKey/appSecret。</p>
 */
@Service
public class SystemApplicationService {

    private final SysSystemApplicationMapper applicationRepository;
    private final SysSystemMapper systemRepository;
    private final SystemService systemService;

    public SystemApplicationService(SysSystemApplicationMapper applicationRepository,
                                    SysSystemMapper systemRepository,
                                    SystemService systemService) {
        this.applicationRepository = applicationRepository;
        this.systemRepository = systemRepository;
        this.systemService = systemService;
    }

    /**
     * SDK 上报（幂等）：
     * 无记录 → 新建 PENDING；PENDING → 覆盖最近信息且保持 PENDING；
     * REJECTED → 重新置 PENDING 并清空审核字段；APPROVED → 直接返回 APPROVED（不重建系统）。
     */
    @Transactional
    public SystemReportResponse report(SystemReportRequest request, String appKey) {
        String code = request.systemCode().trim();
        String name = request.name().trim();
        String region = request.region().trim();
        Optional<SysSystemApplication> existing = applicationRepository.findBySystemCode(code);
        if (existing.isEmpty()) {
            if (systemRepository.existsByCode(code)) {
                throw ApiException.conflict("system already exists: " + code);
            }
            SysSystemApplication application = new SysSystemApplication();
            application.setSystemCode(code);
            application.setName(name);
            application.setRegion(region);
            application.setSource(RbacConstants.APPLICATION_SOURCE_SDK);
            application.setAppKey(blankToNull(appKey));
            application.setSdkVersion(blankToNull(request.sdkVersion()));
            application.setRemark(blankToNull(request.remark()));
            application.setStatus(RbacConstants.APPLICATION_STATUS_PENDING);
            SysSystemApplication saved = applicationRepository.save(application);
            return new SystemReportResponse(saved.getId(), statusName(saved.getStatus()));
        }

        SysSystemApplication application = existing.get();
        if (Integer.valueOf(RbacConstants.APPLICATION_STATUS_APPROVED).equals(application.getStatus())) {
            // 已开通：SDK 每次启动上报保持幂等
            return new SystemReportResponse(application.getId(), statusName(application.getStatus()));
        }
        application.setName(name);
        application.setRegion(region);
        application.setSource(RbacConstants.APPLICATION_SOURCE_SDK);
        application.setAppKey(blankToNull(appKey));
        application.setSdkVersion(blankToNull(request.sdkVersion()));
        application.setRemark(blankToNull(request.remark()));
        application.setStatus(RbacConstants.APPLICATION_STATUS_PENDING);
        application.setRejectReason(null);
        application.setReviewedBy(null);
        application.setReviewedAt(null);
        application.setSystemId(null);
        applicationRepository.save(application);
        return new SystemReportResponse(application.getId(), statusName(application.getStatus()));
    }

    /** 接入申请分页：status 支持 PENDING/APPROVED/REJECTED，空为全部。 */
    @Transactional(readOnly = true)
    public PageResult<SystemApplicationVO> page(String status, PageQuery pageQuery) {
        Integer statusValue = parseStatus(status);
        Page<SysSystemApplication> mpPage = pageQuery.toMpPage();
        List<SysSystemApplication> records = applicationRepository.search(mpPage, statusValue);
        return PageResult.of(records.stream().map(SystemApplicationService::toVO).toList(),
                mpPage.getTotal(), pageQuery);
    }

    /**
     * 审核通过：仅 PENDING 可审；事务内创建系统（admin 角色 + {@code *} + 管理员授权），
     * 回填 status=APPROVED/system_id/reviewed_*；返回新系统凭据（完整 appSecret 仅此一次）。
     */
    @Transactional
    public ApproveResponse approve(Long id, ApproveRequest request, String reviewedBy) {
        SysSystemApplication application = requirePending(id);
        SystemVO system = systemService.create(new SystemCreateRequest(
                application.getSystemCode(),
                application.getName(),
                application.getRegion(),
                request.adminExternalUserId().trim(),
                blankToNull(request.adminName()),
                RbacConstants.STATUS_ENABLED,
                0,
                application.getRemark()));
        application.setStatus(RbacConstants.APPLICATION_STATUS_APPROVED);
        application.setSystemId(system.id());
        application.setReviewedBy(reviewedBy);
        application.setReviewedAt(LocalDateTime.now());
        application.setRejectReason(null);
        applicationRepository.save(application);
        SystemApplicationVO vo = toVO(application);
        return new ApproveResponse(vo.id(), vo.id(), vo.systemCode(), vo.name(), vo.region(),
                vo.source(), system.appKey(), vo.sdkVersion(), vo.remark(), vo.status(),
                vo.rejectReason(), vo.reviewedBy(), vo.reviewedAt(), vo.systemId(),
                vo.createdAt(), system.appSecret());
    }

    /** 驳回：仅 PENDING 可驳回；只更新状态/原因/审核字段。 */
    @Transactional
    public SystemApplicationVO reject(Long id, RejectRequest request, String reviewedBy) {
        SysSystemApplication application = requirePending(id);
        application.setStatus(RbacConstants.APPLICATION_STATUS_REJECTED);
        application.setRejectReason(request.reason().trim());
        application.setReviewedBy(reviewedBy);
        application.setReviewedAt(LocalDateTime.now());
        application.setSystemId(null);
        applicationRepository.save(application);
        return toVO(application);
    }

    private SysSystemApplication requirePending(Long id) {
        SysSystemApplication application = applicationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("system application not found: " + id));
        if (!Integer.valueOf(RbacConstants.APPLICATION_STATUS_PENDING).equals(application.getStatus())) {
            throw ApiException.conflict("system application already reviewed: " + id);
        }
        return application;
    }

    public static String statusName(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case RbacConstants.APPLICATION_STATUS_PENDING -> "PENDING";
            case RbacConstants.APPLICATION_STATUS_APPROVED -> "APPROVED";
            case RbacConstants.APPLICATION_STATUS_REJECTED -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    private static Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> RbacConstants.APPLICATION_STATUS_PENDING;
            case "APPROVED" -> RbacConstants.APPLICATION_STATUS_APPROVED;
            case "REJECTED" -> RbacConstants.APPLICATION_STATUS_REJECTED;
            default -> throw ApiException.badRequest("invalid status: " + status);
        };
    }

    public static SystemApplicationVO toVO(SysSystemApplication application) {
        return new SystemApplicationVO(application.getId(), application.getSystemCode(),
                application.getName(), application.getRegion(), application.getSource(),
                application.getAppKey(), application.getSdkVersion(), application.getRemark(),
                statusName(application.getStatus()), application.getRejectReason(),
                application.getReviewedBy(), TimeUtils.toInstant(application.getReviewedAt()),
                application.getSystemId(), TimeUtils.toInstant(application.getCreatedAt()));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
