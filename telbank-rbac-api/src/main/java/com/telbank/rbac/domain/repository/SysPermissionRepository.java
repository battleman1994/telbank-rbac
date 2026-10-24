package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysPermission;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    List<SysPermission> findAllBySystemIdOrderBySortAscIdAsc(Long systemId);

    List<SysPermission> findAllBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status);

    Optional<SysPermission> findByIdAndSystemId(Long id, Long systemId);

    Optional<SysPermission> findBySystemIdAndCode(Long systemId, String code);

    boolean existsBySystemIdAndCode(Long systemId, String code);

    boolean existsBySystemIdAndCodeAndIdNot(Long systemId, String code, Long id);

    boolean existsBySystemIdAndParentId(Long systemId, Long parentId);

    /** 映射表来源：method 与 api_pattern 均非空且 status=1（需求 §6.8）。 */
    @Query("""
            select p from SysPermission p
            where p.systemId = :systemId
              and p.status = 1
              and p.method is not null and p.method <> ''
              and p.apiPattern is not null and p.apiPattern <> ''
            order by p.sort asc, p.id asc
            """)
    List<SysPermission> findMappedBySystemId(@Param("systemId") Long systemId);
}
