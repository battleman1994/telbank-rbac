package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysAccessApplication;

/**
 * 业务用户临时权限申请单 Mapper。
 *
 * <p>查询统一带 {@code system_id + external_user_id} 过滤，保证申请人只能看到自己的申请；
 * 审批回调使用 {@link #findByApplicationNoForUpdate(String)} 加行锁串行处理。</p>
 */
public interface SysAccessApplicationMapper extends RbacMapper<SysAccessApplication> {

    default Optional<SysAccessApplication> findByApplicationNo(String applicationNo) {
        return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
                .eq(SysAccessApplication::getApplicationNo, applicationNo)));
    }

    /** 审批回调行锁：避免同一申请单被并发重复处理。 */
    default Optional<SysAccessApplication> findByApplicationNoForUpdate(String applicationNo) {
        return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
                .eq(SysAccessApplication::getApplicationNo, applicationNo)
                .last("FOR UPDATE")));
    }

    default boolean existsByApplicationNo(String applicationNo) {
        return selectCount(Wrappers.<SysAccessApplication>lambdaQuery()
                .eq(SysAccessApplication::getApplicationNo, applicationNo)) > 0;
    }

    /** 当前系统 + 申请人的待审批申请（数据库唯一键保证最多一条）。 */
    default Optional<SysAccessApplication> findPending(Long systemId, String externalUserId) {
        return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
                .eq(SysAccessApplication::getSystemId, systemId)
                .eq(SysAccessApplication::getExternalUserId, externalUserId)
                .eq(SysAccessApplication::getStatus, RbacConstants.ACCESS_APPLICATION_STATUS_PENDING)
                .orderByDesc(SysAccessApplication::getId)
                .last("LIMIT 1")));
    }

    /** 我的申请分页（status 为空时查全部，最新申请在前）。 */
    @Select("""
            <script>
            SELECT * FROM sys_access_application
            WHERE system_id = #{systemId}
              AND external_user_id = #{externalUserId}
              <if test="status != null">AND status = #{status}</if>
            ORDER BY id DESC
            </script>
            """)
    List<SysAccessApplication> search(Page<SysAccessApplication> page,
                                      @Param("systemId") Long systemId,
                                      @Param("externalUserId") String externalUserId,
                                      @Param("status") Integer status);
}
