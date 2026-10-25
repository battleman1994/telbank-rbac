package com.telbank.rbac.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.config.RbacProperties.AccessApproval;
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
import com.telbank.rbac.security.ManagementContext;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.web.dto.AccessApplicationDtos.ApplicationVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.CreateRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.OptionsVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RoleOptionVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RolePermissionVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.RoleVO;

/**
 * 业务用户临时权限申请（申请人侧）：可申请选项、我的申请分页与详情。
 *
 * <p>所有方法都要求合法的 {@code X-System-Code} 管理上下文，并强制以
 * {@code system_id + external_user_id} 过滤，申请人只能看到自己的申请单；
 * 不存在 / 他人 / 其他系统的申请单统一返回 404，避免泄露存在性。</p>
 */
@Service
public class AccessApplicationService {

    /** 平台管理员角色不参与自助申请（避免通配权限被申请）。 */
    private static final String ADMIN_ROLE_CODE = "admin";

    private static final int MAX_APPLICATION_ROLES = 20;
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_APPLICATION_NO_ATTEMPTS = 5;
    private static final String APPLICATION_NO_PREFIX = "RA-";
    private static final String APPLICATION_NO_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int APPLICATION_NO_SUFFIX_LENGTH = 6;
    private static final DateTimeFormatter APPLICATION_NO_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SysAccessApplicationMapper applicationRepository;
    private final SysAccessApplicationRoleMapper applicationRoleRepository;
    private final SysRoleMapper roleRepository;
    private final SysUserRoleMapper userRoleRepository;
    private final SysRolePermissionMapper rolePermissionRepository;
    private final SysSystemMapper systemRepository;
    private final ManagementContextService contextService;
    private final CurrentUserProvider currentUserProvider;
    private final RbacProperties properties;

    public AccessApplicationService(SysAccessApplicationMapper applicationRepository,
                                    SysAccessApplicationRoleMapper applicationRoleRepository,
                                    SysRoleMapper roleRepository,
                                    SysUserRoleMapper userRoleRepository,
                                    SysRolePermissionMapper rolePermissionRepository,
                                    SysSystemMapper systemRepository,
                                    ManagementContextService contextService,
                                    CurrentUserProvider currentUserProvider,
                                    RbacProperties properties) {
        this.applicationRepository = applicationRepository;
        this.applicationRoleRepository = applicationRoleRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.systemRepository = systemRepository;
        this.contextService = contextService;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
    }

    /**
     * 创建临时权限申请（待审批）：校验角色归属/启用/非 admin、当前未持有、无其他待审批单，
     * 生成全局唯一申请单号并写主表 + 角色快照；数据库唯一键兜底并发冲突。
     */
    @Transactional
    public ApplicationVO create(CreateRequest request) {
        ManagementContext context = contextService.requireContext();
        String externalUserId = currentUserProvider.requireExternalUserId();

        if (request.roleIds() == null || request.roleIds().isEmpty()) {
            throw ApiException.badRequest("roleIds 不能为空");
        }
        if (request.roleIds().size() > MAX_APPLICATION_ROLES) {
            throw ApiException.badRequest("一次最多申请 " + MAX_APPLICATION_ROLES + " 个角色");
        }
        if (request.roleIds().stream().anyMatch(roleId -> roleId == null)) {
            throw ApiException.badRequest("roleIds 不能包含空值");
        }
        List<Long> roleIds = request.roleIds().stream().distinct().toList();
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.isEmpty()) {
            throw ApiException.badRequest("申请原因不能为空");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("申请原因不能超过 " + MAX_REASON_LENGTH + " 字");
        }

        List<SysRole> roles = roleIds.stream()
                .map(roleId -> roleRepository.findByIdAndSystemId(roleId, context.systemId())
                        .orElseThrow(() -> ApiException.badRequest("角色不属于当前系统")))
                .toList();
        if (roles.stream().anyMatch(role -> role.getStatus() == null
                || !Integer.valueOf(RbacConstants.STATUS_ENABLED).equals(role.getStatus()))) {
            throw ApiException.badRequest("角色已停用，不能申请");
        }
        if (roles.stream().anyMatch(role -> ADMIN_ROLE_CODE.equals(role.getCode()))) {
            throw ApiException.badRequest("系统管理员角色不可自助申请");
        }

        Set<Long> ownedRoleIds = new HashSet<>(
                userRoleRepository.findActiveRoleIds(context.systemId(), externalUserId));
        if (roles.stream().anyMatch(role -> ownedRoleIds.contains(role.getId()))) {
            throw ApiException.badRequest("已拥有该角色，无需申请");
        }
        applicationRepository.findPending(context.systemId(), externalUserId)
                .ifPresent(pending -> {
                    throw ApiException.conflict("已有待审批申请，请等待审批完成");
                });

        SysAccessApplication application = new SysAccessApplication();
        application.setSystemId(context.systemId());
        application.setExternalUserId(externalUserId);
        application.setStatus(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
        application.setReason(reason);
        application.setApplicationNo(nextApplicationNo());
        try {
            applicationRepository.save(application);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("已有待审批申请，请等待审批完成");
        }

        for (SysRole role : roles) {
            SysAccessApplicationRole snapshot = new SysAccessApplicationRole();
            snapshot.setApplicationId(application.getId());
            snapshot.setRoleId(role.getId());
            snapshot.setRoleCode(role.getCode());
            snapshot.setRoleName(role.getName());
            applicationRoleRepository.save(snapshot);
        }
        // created_at 由数据库默认值生成，插入后回读一次，保证创建响应与列表/详情一致。
        SysAccessApplication saved = applicationRepository.findById(application.getId())
                .orElse(application);
        return detailByEntity(saved, context);
    }

    /** 生成 {@code RA-yyyyMMdd-XXXXXX} 申请单号，先查重，最多重试 5 次。 */
    private String nextApplicationNo() {
        String datePart = LocalDate.now(ZoneOffset.UTC)
                .format(APPLICATION_NO_DATE_FORMAT);
        for (int attempt = 0; attempt < MAX_APPLICATION_NO_ATTEMPTS; attempt++) {
            String applicationNo = APPLICATION_NO_PREFIX + datePart + "-" + randomSuffix();
            if (!applicationRepository.existsByApplicationNo(applicationNo)) {
                return applicationNo;
            }
        }
        throw new ApiException(500, "申请单号生成失败，请重试");
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(APPLICATION_NO_SUFFIX_LENGTH);
        for (int index = 0; index < APPLICATION_NO_SUFFIX_LENGTH; index++) {
            suffix.append(APPLICATION_NO_ALPHABET.charAt(
                    SECURE_RANDOM.nextInt(APPLICATION_NO_ALPHABET.length())));
        }
        return suffix.toString();
    }

    /**
     * 当前系统可申请的角色：启用、排除 admin；owned 表示申请人已有效持有；
     * permissions 为角色当前有效权限的预览（批量查询，避免 N+1）。
     */
    @Transactional(readOnly = true)
    public OptionsVO options() {
        ManagementContext context = contextService.requireContext();
        String externalUserId = currentUserProvider.requireExternalUserId();
        Set<Long> ownedRoleIds = new HashSet<>(
                userRoleRepository.findActiveRoleIds(context.systemId(), externalUserId));
        List<SysRole> roles = roleRepository
                .findAllBySystemIdAndStatusOrderBySortAscIdAsc(context.systemId(),
                        RbacConstants.STATUS_ENABLED)
                .stream()
                .filter(role -> !ADMIN_ROLE_CODE.equals(role.getCode()))
                .toList();
        Map<Long, List<RolePermissionVO>> permissions = loadRolePermissions(context.systemId(),
                roles.stream().map(SysRole::getId).toList());
        List<RoleOptionVO> roleOptions = roles.stream()
                .map(role -> new RoleOptionVO(role.getId(), role.getCode(), role.getName(),
                        ownedRoleIds.contains(role.getId()),
                        permissions.getOrDefault(role.getId(), List.of())))
                .toList();
        SysSystem system = systemRepository.findById(context.systemId())
                .orElseThrow(() -> ApiException.notFound("业务系统不存在"));
        return new OptionsVO(context.systemId(), context.systemCode(), system.getName(),
                properties.getAccessApproval().getUrlTemplate(), roleOptions);
    }

    /** 我的申请分页：只查当前系统 + 当前申请人；status 为空表示全部。 */
    @Transactional(readOnly = true)
    public PageResult<ApplicationVO> page(String status, PageQuery pageQuery) {
        ManagementContext context = contextService.requireContext();
        String externalUserId = currentUserProvider.requireExternalUserId();
        Integer statusValue = parseStatus(status);
        Page<SysAccessApplication> mpPage = pageQuery.toMpPage();
        List<SysAccessApplication> records = applicationRepository.search(mpPage,
                context.systemId(), externalUserId, statusValue);
        return PageResult.of(toVOs(records, context), mpPage.getTotal(), pageQuery);
    }

    /** 我的申请详情：必须属于当前系统 + 当前申请人，否则统一 404。 */
    @Transactional(readOnly = true)
    public ApplicationVO detail(String applicationNo) {
        ManagementContext context = contextService.requireContext();
        String externalUserId = currentUserProvider.requireExternalUserId();
        SysAccessApplication application = applicationRepository.findByApplicationNo(applicationNo)
                .filter(candidate -> context.systemId().equals(candidate.getSystemId()))
                .filter(candidate -> externalUserId.equals(candidate.getExternalUserId()))
                .orElseThrow(() -> ApiException.notFound("申请单不存在"));
        return detailByEntity(application, context);
    }

    /** 单条申请单转 VO（创建申请后复用，避免重复实现归属校验之外的转换逻辑）。 */
    private ApplicationVO detailByEntity(SysAccessApplication application, ManagementContext context) {
        return toVOs(List.of(application), context).get(0);
    }

    /** 按申请单批量加载角色快照，避免逐单查询（N+1）。 */
    private List<ApplicationVO> toVOs(List<SysAccessApplication> applications,
                                      ManagementContext context) {
        if (applications.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SysAccessApplicationRole>> snapshotsByApplicationId =
                applicationRoleRepository.findByApplicationIdInOrderByIdAsc(
                                applications.stream().map(SysAccessApplication::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(SysAccessApplicationRole::getApplicationId));
        return applications.stream()
                .map(application -> toVO(application, context,
                        snapshotsByApplicationId.getOrDefault(application.getId(), List.of())))
                .toList();
    }

    private ApplicationVO toVO(SysAccessApplication application, ManagementContext context,
                               List<SysAccessApplicationRole> snapshots) {
        List<RoleVO> roles = snapshots.stream()
                .map(snapshot -> new RoleVO(snapshot.getRoleId(), snapshot.getRoleCode(),
                        snapshot.getRoleName()))
                .toList();
        return new ApplicationVO(application.getId(), application.getApplicationNo(),
                application.getSystemId(), context.systemCode(), statusName(application.getStatus()),
                roles.size(), roles, application.getReason(),
                TimeUtils.toInstant(application.getApprovedValidFrom()),
                TimeUtils.toInstant(application.getApprovedValidUntil()),
                application.getDecisionComment(), application.getReviewedBy(),
                TimeUtils.toInstant(application.getReviewedAt()),
                TimeUtils.toInstant(application.getCreatedAt()),
                resolveApprovalUrl(application));
    }

    /** 仅待审批申请单返回审批链接；模板缺少占位符时返回 null（配置问题已在启动时 WARN）。 */
    private String resolveApprovalUrl(SysAccessApplication application) {
        if (!Integer.valueOf(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING)
                .equals(application.getStatus())) {
            return null;
        }
        String template = properties.getAccessApproval().getUrlTemplate();
        if (template == null || template.isBlank()
                || !template.contains(AccessApproval.APPLICATION_NO_PLACEHOLDER)
                || application.getApplicationNo() == null) {
            return null;
        }
        return template.replace(AccessApproval.APPLICATION_NO_PLACEHOLDER,
                application.getApplicationNo());
    }

    /** 批量加载角色当前有效权限（授权启用 + 时效内 + 权限启用）。 */
    private Map<Long, List<RolePermissionVO>> loadRolePermissions(Long systemId,
                                                                  List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return rolePermissionRepository.findActivePermissionRows(systemId, roleIds).stream()
                .collect(Collectors.groupingBy(AccessRolePermissionRow::getRoleId,
                        Collectors.mapping(row -> new RolePermissionVO(row.getId(), row.getCode(),
                                        row.getName(), row.getType()),
                                Collectors.toList())));
    }

    /** 状态数字转字符串（null 保持 null，未知值 "UNKNOWN"），供分页/详情/审批回调复用。 */
    public static String statusName(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case RbacConstants.ACCESS_APPLICATION_STATUS_PENDING -> "PENDING";
            case RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED -> "APPROVED";
            case RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    /** 状态字符串解析：空/null 表示全部，仅接受 PENDING/APPROVED/REJECTED，未知值 400。 */
    private static Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> RbacConstants.ACCESS_APPLICATION_STATUS_PENDING;
            case "APPROVED" -> RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED;
            case "REJECTED" -> RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED;
            default -> throw ApiException.badRequest("非法申请状态");
        };
    }
}
