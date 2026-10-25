package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;

/**
 * 过期授权自动收回定时任务（需求 §5.5 / 契约 §6）。
 *
 * <p>把 {@code valid_until <= NOW()} 且 {@code auto_revoke=1} 且 {@code status=1} 的
 * {@code sys_user_role} 记录置为 {@code status=0}；若有记录被更新，按 system_id 分组发布 Redis 变更通知。</p>
 *
 * <p>A21：角色-权限不再带时效，定时扫描只处理用户-角色关系。</p>
 *
 * <p>注意：到期即视为无权限（权限查询 SQL 带时效过滤），本任务只做状态清理与通知，
 * 不依赖它保证安全边界。</p>
 */
@Service
public class ExpiryScanService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScanService.class);

    private final SysUserRoleMapper userRoleRepository;
    private final ChangeNotifyService changeNotifyService;

    public ExpiryScanService(SysUserRoleMapper userRoleRepository,
                             ChangeNotifyService changeNotifyService) {
        this.userRoleRepository = userRoleRepository;
        this.changeNotifyService = changeNotifyService;
    }

    @Scheduled(cron = "${rbac.expiry-scan-cron:0 * * * * *}")
    @Transactional
    public void scanAndRevoke() {
        Set<Long> affectedSystemIds = new LinkedHashSet<>(userRoleRepository.findExpiredSystemIds());
        int revoked = userRoleRepository.revokeExpired();
        if (revoked == 0) {
            log.debug("expiry scan: no expired grants to revoke");
            return;
        }
        log.info("expiry scan revoked {} user-role grants, affected systems={}", revoked, affectedSystemIds);
        for (Long systemId : new ArrayList<>(affectedSystemIds)) {
            changeNotifyService.publishAfterCommit(systemId, RbacConstants.CHANGE_TYPE_PERMISSION);
        }
    }
}
