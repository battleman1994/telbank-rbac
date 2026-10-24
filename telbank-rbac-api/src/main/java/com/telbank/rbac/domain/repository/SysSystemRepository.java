package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telbank.rbac.domain.entity.SysSystem;

public interface SysSystemRepository extends JpaRepository<SysSystem, Long> {

    Optional<SysSystem> findByCode(String code);

    boolean existsByCode(String code);

    Optional<SysSystem> findFirstByIsPlatformOrderByIdAsc(Integer isPlatform);

    /** 系统接入列表：仅业务系统（排除平台系统），含管理员信息。 */
    @Query("""
            select s from SysSystem s
            where (s.isPlatform is null or s.isPlatform = 0)
              and (:keyword = ''
                   or lower(s.code) like lower(concat('%', :keyword, '%'))
                   or lower(s.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<SysSystem> search(@Param("keyword") String keyword, Pageable pageable);

    /** 全部启用的业务系统（排除平台系统），按 sort,id 升序。 */
    @Query("""
            select s from SysSystem s
            where s.status = :status
              and (s.isPlatform is null or s.isPlatform = 0)
            order by s.sort asc, s.id asc
            """)
    List<SysSystem> findEnabledBusinessSystems(@Param("status") Integer status);
}
