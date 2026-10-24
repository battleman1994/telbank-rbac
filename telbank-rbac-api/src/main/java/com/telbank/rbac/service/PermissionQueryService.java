package com.telbank.rbac.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.domain.mapper.row.PermissionValidityRow;
import com.telbank.rbac.web.dto.OpenApiDtos.PermissionItem;

/**
 * 权限查询服务（需求 §5.2 / §5.3）：
 *
 * <ul>
 *   <li>JWT sub → external_user_id；平台超管（平台系统内 {@code *}，数据驱动）会得到 {@code *}；</li>
 *   <li>权限查询 SQL 自带时效过滤：{@code (valid_from IS NULL OR valid_from <= NOW()) AND
 *       (valid_until IS NULL OR valid_until > NOW()) AND status = 1}；</li>
 *   <li>{@code is_public=1} 的权限码所有登录用户默认拥有。</li>
 * </ul>
 */
@Service
public class PermissionQueryService {

    private final SysUserRoleMapper userRoleRepository;
    private final PlatformAdminService platformAdminService;

    public PermissionQueryService(SysUserRoleMapper userRoleRepository,
                                  PlatformAdminService platformAdminService) {
        this.userRoleRepository = userRoleRepository;
        this.platformAdminService = platformAdminService;
    }

    /**
     * 当前生效的权限码集合（含公共权限）。
     */
    @Transactional(readOnly = true)
    public Set<String> effectiveCodes(Long systemId, String externalUserId) {
        if (systemId == null || externalUserId == null || externalUserId.isBlank()) {
            return Set.of();
        }
        List<PermissionItem> items = effectivePermissions(systemId, externalUserId);
        Set<String> codes = new LinkedHashSet<>(items.size());
        for (PermissionItem item : items) {
            if (item.code() != null && !item.code().isBlank()) {
                codes.add(item.code());
            }
        }
        return codes;
    }

    /**
     * 当前生效的权限码 + 时效包络（契约 §4.2 user-permissions）。
     */
    @Transactional(readOnly = true)
    public List<PermissionItem> effectivePermissions(Long systemId, String externalUserId) {
        if (systemId == null || externalUserId == null || externalUserId.isBlank()) {
            return List.of();
        }
        List<PermissionValidityRow> rows =
                userRoleRepository.findEffectivePermissionValidity(systemId, externalUserId);
        List<PermissionItem> items = new ArrayList<>(rows.size());
        for (PermissionValidityRow row : rows) {
            String code = row.getCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            LocalDateTime validFrom = asDateTime(row.getValidFrom());
            LocalDateTime validUntil = asDateTime(row.getValidUntil());
            items.add(new PermissionItem(code, TimeUtils.toInstant(validFrom), TimeUtils.toInstant(validUntil)));
        }
        return items;
    }

    /**
     * 开放接口 user-permissions：平台超管返回 {@code *}（契约 §4.2）。
     */
    @Transactional(readOnly = true)
    public List<PermissionItem> userPermissions(SysSystem system, String externalUserId) {
        if (platformAdminService.isPlatformAdmin(externalUserId)) {
            return List.of(new PermissionItem(RbacConstants.PLATFORM_ADMIN_PERMISSION_CODE, null, null));
        }
        return effectivePermissions(system.getId(), externalUserId);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 原生 SQL 用 DATE_FORMAT 输出 ISO 字符串；兼容驱动直接返回时间类型的情况。
     */
    private static LocalDateTime asDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ex) {
            try {
                return java.time.ZonedDateTime.parse(text).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
