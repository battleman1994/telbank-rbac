package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysPageAction;

public interface SysPageActionRepository extends JpaRepository<SysPageAction, Long> {

    @Query("""
            select a from SysPageAction a
            where a.systemId = :systemId
              and (:pageCode = '' or a.pageCode = :pageCode)
            order by a.pageCode asc, a.sort asc, a.id asc
            """)
    Page<SysPageAction> search(@Param("systemId") Long systemId,
                               @Param("pageCode") String pageCode,
                               Pageable pageable);

    List<SysPageAction> findBySystemIdAndStatusOrderBySortAscIdAsc(Long systemId, Integer status);

    List<SysPageAction> findBySystemIdAndPageCodeAndStatusOrderBySortAscIdAsc(
            Long systemId, String pageCode, Integer status);

    boolean existsBySystemIdAndPageCodeAndCode(Long systemId, String pageCode, String code);

    boolean existsBySystemIdAndPageCodeAndCodeAndIdNot(Long systemId, String pageCode, String code, Long id);
}
