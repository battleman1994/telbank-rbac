package com.telbank.rbac.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.web.dto.SystemDtos.SystemCreateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemUpdateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemVO;

/**
 * 系统管理（契约 §3.2）。
 */
@Service
public class SystemService {

    private final SysSystemRepository systemRepository;

    public SystemService(SysSystemRepository systemRepository) {
        this.systemRepository = systemRepository;
    }

    @Transactional(readOnly = true)
    public PageResult<SystemVO> page(String keyword, PageQuery pageQuery) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<SysSystem> page = systemRepository.search(normalizedKeyword,
                pageQuery.toPageable(Sort.by(Sort.Order.asc("sort"), Sort.Order.asc("id"))));
        return PageResult.of(page.getContent().stream().map(SystemService::toVO).toList(),
                page.getTotalElements(), pageQuery);
    }

    @Transactional
    public SystemVO create(SystemCreateRequest request) {
        String code = request.code().trim();
        if (systemRepository.existsByCode(code)) {
            throw ApiException.conflict("system code already exists: " + code);
        }
        SysSystem system = new SysSystem();
        system.setCode(code);
        system.setName(request.name().trim());
        system.setRegion(request.region().trim());
        system.setStatus(request.status() == null ? RbacConstants.STATUS_ENABLED : request.status());
        system.setSort(request.sort() == null ? 0 : request.sort());
        system.setRemark(request.remark());
        return toVO(systemRepository.save(system));
    }

    @Transactional
    public SystemVO update(Long id, SystemUpdateRequest request) {
        SysSystem system = requireById(id);
        system.setName(request.name().trim());
        system.setRegion(request.region().trim());
        if (request.status() != null) {
            system.setStatus(request.status());
        }
        if (request.sort() != null) {
            system.setSort(request.sort());
        }
        if (request.remark() != null) {
            system.setRemark(request.remark());
        }
        return toVO(systemRepository.save(system));
    }

    /**
     * DELETE 为停用，不做物理删除（契约 §3.2）。
     */
    @Transactional
    public void disable(Long id) {
        SysSystem system = requireById(id);
        system.setStatus(RbacConstants.STATUS_DISABLED);
        systemRepository.save(system);
    }

    @Transactional(readOnly = true)
    public SysSystem requireById(Long id) {
        return systemRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("system not found: " + id));
    }

    @Transactional(readOnly = true)
    public SysSystem requireByCode(String code) {
        return systemRepository.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("system not found: " + code));
    }

    public static SystemVO toVO(SysSystem system) {
        return new SystemVO(system.getId(), system.getCode(), system.getName(), system.getRegion(),
                system.getStatus(), system.getSort(), system.getRemark(),
                TimeUtils.toInstant(system.getCreatedAt()), TimeUtils.toInstant(system.getUpdatedAt()));
    }
}
