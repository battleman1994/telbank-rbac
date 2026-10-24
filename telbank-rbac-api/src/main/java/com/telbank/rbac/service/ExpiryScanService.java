package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.repository.SysRolePermissionRepository;
import com.telbank.rbac.domain.repository.SysUserRoleRepository;

/**
 * 过期授权自动收回定时任务（需求 §5.5 / 契约 §6）。
 *
 * <p>把 {@code valid_until <= NOW()} 且 {@code auto_revoke=1} 且 {@code status=1} 的
 * {@code sys_user_role} / {@code sys_role_permission} 记录置为 {@code status=0}；
 * 若有记录被更新，按 system_id 分组发布 Redis 变更通知。</p>
 *
 * <p>注意：到期即视为无权限（权限查询 SQL 带时效过滤），本任务只做状态清理与通知，
 * 不依赖它保证安全边界。</p>
 */
@Service
public class ExpiryScanService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScanService.class);

    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final ChangeNotifyService changeNotifyService;

    public ExpiryScanService(SysUserRoleRepository userRoleRepository,
                             SysRolePermissionRepository rolePermissionRepository,
                             ChangeNotifyService changeNotifyService) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.changeNotifyService = changeNotifyService;
    }

    @Scheduled(cron = "${rbac.expiry-scan-cron:0 * * * * *}")
    @Transactional
    public void scanAndRevoke() {
        Set<Long> affectedSystemIds = new LinkedHashSet<>();
        affectedSystemIds.addAll(userRoleRepository.findExpiredSystemIds());
        affectedSystemIds.addAll(rolePermissionRepository.findExpiredSystemIds());

        int revokedUserRoles = userRoleRepository.revokeExpired();
        int revokedRolePermissions = rolePermissionRepository.revokeExpired();
        int total = revokedUserRoles + revokedRolePermissions;
        if (total == 0) {
            log.debug("expiry scan: no expired grants to revoke");
            return;
        }
        log.info("expiry scan revoked {} user-role grants and {} role-permission grants, affected systems={}",
                revokedUserRoles, revokedRolePermissions, affectedSystemIds);
        for (Long systemId : new ArrayList<>(affectedSystemIds)) {
            changeNotifyService.publishAfterCommit(systemId, RbacConstants.CHANGE_TYPE_PERMISSION);
        }
    }
}
