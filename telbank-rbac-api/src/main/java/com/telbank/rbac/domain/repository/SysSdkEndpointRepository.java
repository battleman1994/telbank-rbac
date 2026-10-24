package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysSdkEndpoint;

public interface SysSdkEndpointRepository extends JpaRepository<SysSdkEndpoint, Long> {

    Optional<SysSdkEndpoint> findBySystemIdAndMethodAndPath(Long systemId, String method, String path);

    List<SysSdkEndpoint> findByPermissionId(Long permissionId);

    List<SysSdkEndpoint> findByIdIn(List<Long> ids);

    @Query("""
            select e from SysSdkEndpoint e
            where e.systemId = :systemId
              and (:keyword = ''
                   or lower(e.method) like lower(concat('%', :keyword, '%'))
                   or lower(e.path) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(e.candidateCode, '')) like lower(concat('%', :keyword, '%')))
            order by e.id desc
            """)
    Page<SysSdkEndpoint> search(@Param("systemId") Long systemId,
                                @Param("keyword") String keyword,
                                Pageable pageable);
}
