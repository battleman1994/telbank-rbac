package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.SysSystem;

public interface SysSystemMapper extends RbacMapper<SysSystem> {

    default Optional<SysSystem> findByCode(String code) {
        return Optional.ofNullable(selectOne(Wrappers.<SysSystem>lambdaQuery()
                .eq(SysSystem::getCode, code)
                .last("LIMIT 1")));
    }

    default boolean existsByCode(String code) {
        return selectCount(Wrappers.<SysSystem>lambdaQuery().eq(SysSystem::getCode, code)) > 0;
    }

    default Optional<SysSystem> findFirstByIsPlatformOrderByIdAsc(Integer isPlatform) {
        return Optional.ofNullable(selectOne(Wrappers.<SysSystem>lambdaQuery()
                .eq(SysSystem::getIsPlatform, isPlatform)
                .orderByAsc(SysSystem::getId)
                .last("LIMIT 1")));
    }

    /** 系统接入列表（排除平台系统，关键字匹配 code/name，sort,id 升序）。 */
    @Select("""
            <script>
            SELECT * FROM sys_system
            WHERE (is_platform IS NULL OR is_platform = 0)
            <if test="keyword != null and keyword != ''">
              AND (LOWER(code) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                   OR LOWER(name) LIKE CONCAT('%', LOWER(#{keyword}), '%'))
            </if>
            ORDER BY sort ASC, id ASC
            </script>
            """)
    List<SysSystem> search(Page<SysSystem> page, @Param("keyword") String keyword);

    /** 全部启用的业务系统（排除平台系统）。 */
    @Select("""
            SELECT * FROM sys_system
            WHERE status = #{status} AND (is_platform IS NULL OR is_platform = 0)
            ORDER BY sort ASC, id ASC
            """)
    List<SysSystem> findEnabledBusinessSystems(@Param("status") Integer status);
}
