package com.telbank.rbac.domain.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.SysSdkEndpoint;

public interface SysSdkEndpointMapper extends RbacMapper<SysSdkEndpoint> {

    default Optional<SysSdkEndpoint> findBySystemIdAndMethodAndPath(
            Long systemId, String method, String path) {
        return Optional.ofNullable(selectOne(Wrappers.<SysSdkEndpoint>lambdaQuery()
                .eq(SysSdkEndpoint::getSystemId, systemId)
                .eq(SysSdkEndpoint::getMethod, method)
                .eq(SysSdkEndpoint::getPath, path)
                .last("LIMIT 1")));
    }

    default List<SysSdkEndpoint> findByPermissionId(Long permissionId) {
        return selectList(Wrappers.<SysSdkEndpoint>lambdaQuery()
                .eq(SysSdkEndpoint::getPermissionId, permissionId));
    }

    default List<SysSdkEndpoint> findByIdIn(List<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : selectBatchIds(ids);
    }

    @Select("""
            <script>
            SELECT * FROM sys_sdk_endpoint
            WHERE system_id = #{systemId}
            <if test="keyword != null and keyword != ''">
              AND (LOWER(method) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                   OR LOWER(path) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                   OR LOWER(COALESCE(candidate_code, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%'))
            </if>
            ORDER BY id DESC
            </script>
            """)
    List<SysSdkEndpoint> search(Page<SysSdkEndpoint> page, @Param("systemId") Long systemId,
                                @Param("keyword") String keyword);
}
