package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期授权自动收回定时任务（需求 §5.5）：置 status=0 并按 system 分组发布通知。
 * A21：角色-权限不再带时效，只扫描用户-角色关系。
 */
class ExpiryScanServiceTest {

    @Test
    void revokesExpiredGrantsAndPublishesOncePerAffectedSystem() {
        List<String> published = new ArrayList<>();

        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findExpiredSystemIds" -> List.of(1L, 2L);
                    case "revokeExpired" -> 3;
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        ChangeNotifyService changeNotifyService = new ChangeNotifyService(null, null, null, null, null) {
            @Override
            public void publishAfterCommit(Long systemId, String changeType) {
                published.add(systemId + ":" + changeType);
            }
        };

        new ExpiryScanService(userRoleRepository, changeNotifyService).scanAndRevoke();

        assertThat(published).containsExactlyInAnyOrder(
                "1:" + RbacConstants.CHANGE_TYPE_PERMISSION,
                "2:" + RbacConstants.CHANGE_TYPE_PERMISSION);
    }

    @Test
    void doesNotPublishWhenNothingWasRevoked() {
        List<String> published = new ArrayList<>();

        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findExpiredSystemIds" -> List.of();
                    case "revokeExpired" -> 0;
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        ChangeNotifyService changeNotifyService = new ChangeNotifyService(null, null, null, null, null) {
            @Override
            public void publishAfterCommit(Long systemId, String changeType) {
                published.add(systemId + ":" + changeType);
            }
        };

        new ExpiryScanService(userRoleRepository, changeNotifyService).scanAndRevoke();

        assertThat(published).isEmpty();
    }
}
