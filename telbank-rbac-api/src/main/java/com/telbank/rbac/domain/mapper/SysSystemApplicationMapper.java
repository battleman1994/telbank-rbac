package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.SysSystemApplication;

/**
 * 系统接入申请 Mapper（A16）。{@code system_code} 唯一，同一系统仅保留一条申请记录。
 */
public interface SysSystemApplicationMapper extends RbacMapper<SysSystemApplication> {

    default Optional<SysSystemApplication> findBySystemCode(String systemCode) {
        return Optional.ofNullable(selectOne(Wrappers.<SysSystemApplication>lambdaQuery()
                .eq(SysSystemApplication::getSystemCode, systemCode)
                .last("LIMIT 1")));
    }

    /** 接入申请分页（status 为空时查全部，最新申请在前）。 */
    @Select("""
            <script>
            SELECT * FROM sys_system_application
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            ORDER BY id DESC
            </script>
            """)
    List<SysSystemApplication> search(Page<SysSystemApplication> page, @Param("status") Integer status);
}
