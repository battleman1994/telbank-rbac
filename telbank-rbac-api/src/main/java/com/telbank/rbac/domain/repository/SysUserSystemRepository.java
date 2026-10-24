package com.telbank.rbac.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.telbank.rbac.domain.entity.SysUserSystem;

public interface SysUserSystemRepository extends JpaRepository<SysUserSystem, Long> {

    Optional<SysUserSystem> findBySystemIdAndExternalUserId(Long systemId, String externalUserId);

    List<SysUserSystem> findByExternalUserIdOrderByIdAsc(String externalUserId);

    List<SysUserSystem> findByExternalUserIdAndStatusOrderByIsDefaultDescIdAsc(
            String externalUserId, Integer status);
}
