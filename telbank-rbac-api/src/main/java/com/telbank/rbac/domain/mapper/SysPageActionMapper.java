package com.telbank.rbac.domain.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.SysPageAction;

public interface SysPageActionMapper extends RbacMapper<SysPageAction> {

    default List<SysPageAction> findBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status) {
        return selectList(Wrappers.<SysPageAction>lambdaQuery()
                .eq(SysPageAction::getSystemId, systemId)
                .eq(SysPageAction::getStatus, status)
                .orderByAsc(SysPageAction::getPageCode)
                .orderByAsc(SysPageAction::getSort)
                .orderByAsc(SysPageAction::getId));
    }

    default List<SysPageAction> findBySystemIdAndPageCodeAndStatusOrderBySortAscIdAsc(
            Long systemId, String pageCode, Integer status) {
        return selectList(Wrappers.<SysPageAction>lambdaQuery()
                .eq(SysPageAction::getSystemId, systemId)
                .eq(SysPageAction::getPageCode, pageCode)
                .eq(SysPageAction::getStatus, status)
                .orderByAsc(SysPageAction::getSort)
                .orderByAsc(SysPageAction::getId));
    }

    default boolean existsBySystemIdAndPageCodeAndCode(Long systemId, String pageCode, String code) {
        return selectCount(Wrappers.<SysPageAction>lambdaQuery()
                .eq(SysPageAction::getSystemId, systemId)
                .eq(SysPageAction::getPageCode, pageCode)
                .eq(SysPageAction::getCode, code)) > 0;
    }

    default boolean existsBySystemIdAndPageCodeAndCodeAndIdNot(
            Long systemId, String pageCode, String code, Long id) {
        return selectCount(Wrappers.<SysPageAction>lambdaQuery()
                .eq(SysPageAction::getSystemId, systemId)
                .eq(SysPageAction::getPageCode, pageCode)
                .eq(SysPageAction::getCode, code)
                .ne(SysPageAction::getId, id)) > 0;
    }

    @Select("""
            <script>
            SELECT * FROM sys_page_action
            WHERE system_id = #{systemId}
            <if test="pageCode != null and pageCode != ''">
              AND page_code = #{pageCode}
            </if>
            ORDER BY page_code ASC, sort ASC, id ASC
            </script>
            """)
    List<SysPageAction> search(Page<SysPageAction> page, @Param("systemId") Long systemId,
                               @Param("pageCode") String pageCode);
}
