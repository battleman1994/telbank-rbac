package com.telbank.rbac.domain.repository;

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

    @Query("""
            select s from SysSystem s
            where (:keyword = ''
                   or lower(s.code) like lower(concat('%', :keyword, '%'))
                   or lower(s.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<SysSystem> search(@Param("keyword") String keyword, Pageable pageable);

    java.util.List<SysSystem> findAllByStatusOrderBySortAscIdAsc(Integer status);
}
