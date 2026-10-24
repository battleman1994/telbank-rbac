package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysRole;

public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    Optional<SysRole> findByIdAndSystemId(Long id, Long systemId);

    Optional<SysRole> findBySystemIdAndCode(Long systemId, String code);

    boolean existsBySystemIdAndCode(Long systemId, String code);

    boolean existsBySystemIdAndCodeAndIdNot(Long systemId, String code, Long id);

    List<SysRole> findAllBySystemIdOrderBySortAscIdAsc(Long systemId);

    List<SysRole> findAllBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status);

    @Query("""
            select r from SysRole r
            where r.systemId = :systemId
              and (:keyword = ''
                   or lower(r.code) like lower(concat('%', :keyword, '%'))
                   or lower(r.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<SysRole> search(@Param("systemId") Long systemId,
                         @Param("keyword") String keyword,
                         Pageable pageable);
}
