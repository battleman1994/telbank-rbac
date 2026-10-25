package com.telbank.rbac.domain.mapper;

import java.util.Collection;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.domain.entity.SysAccessApplicationRole;

/**
 * 申请单角色快照 Mapper。
 */
public interface SysAccessApplicationRoleMapper extends RbacMapper<SysAccessApplicationRole> {

    default List<SysAccessApplicationRole> findByApplicationIdOrderByIdAsc(Long applicationId) {
        return selectList(Wrappers.<SysAccessApplicationRole>lambdaQuery()
                .eq(SysAccessApplicationRole::getApplicationId, applicationId)
                .orderByAsc(SysAccessApplicationRole::getId));
    }

    /** 批量读取多张申请单的角色快照，避免分页列表 N+1。 */
    default List<SysAccessApplicationRole> findByApplicationIdInOrderByIdAsc(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<SysAccessApplicationRole>lambdaQuery()
                .in(SysAccessApplicationRole::getApplicationId, applicationIds)
                .orderByAsc(SysAccessApplicationRole::getApplicationId)
                .orderByAsc(SysAccessApplicationRole::getId));
    }
}
