package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.domain.repository.SysUserSystemRepository;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.OpenApiDtos.UserSystemItem;
import com.telbank.rbac.web.dto.UserSystemDtos.UserSystemCreateRequest;
import com.telbank.rbac.web.dto.UserSystemDtos.UserSystemVO;

/**
 * 用户-系统开通（契约 §3.7）以及当前用户可访问系统列表（契约 §3.1 / §4.2）。
 */
@Service
public class UserSystemService {

    private final SysUserSystemRepository userSystemRepository;
    private final SysSystemRepository systemRepository;
    private final PermissionQueryService permissionQueryService;

    public UserSystemService(SysUserSystemRepository userSystemRepository,
                             SysSystemRepository systemRepository,
                             PermissionQueryService permissionQueryService) {
        this.userSystemRepository = userSystemRepository;
        this.systemRepository = systemRepository;
        this.permissionQueryService = permissionQueryService;
    }

    @Transactional(readOnly = true)
    public List<UserSystemVO> list(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            throw ApiException.badRequest("externalUserId is required");
        }
        List<SysUserSystem> relations = userSystemRepository.findByExternalUserIdOrderByIdAsc(externalUserId.trim());
        Map<Long, SysSystem> systems = loadSystems(relations);
        List<UserSystemVO> result = new ArrayList<>(relations.size());
        for (SysUserSystem relation : relations) {
            SysSystem system = systems.get(relation.getSystemId());
            result.add(new UserSystemVO(relation.getId(), relation.getSystemId(),
                    system == null ? null : system.getCode(), relation.getExternalUserId(),
                    isDefault(relation), relation.getStatus()));
        }
        return result;
    }

    @Transactional
    public UserSystemVO create(UserSystemCreateRequest request) {
        SysSystem system = systemRepository.findById(request.systemId())
                .orElseThrow(() -> ApiException.notFound("system not found: " + request.systemId()));
        String externalUserId = request.externalUserId().trim();
        boolean makeDefault = Boolean.TRUE.equals(request.isDefault());
        List<SysUserSystem> existingRows = userSystemRepository.findByExternalUserIdOrderByIdAsc(externalUserId);
        SysUserSystem relation = existingRows.stream()
                .filter(row -> row.getSystemId().equals(request.systemId()))
                .findFirst()
                .orElseGet(SysUserSystem::new);
        relation.setSystemId(request.systemId());
        relation.setExternalUserId(externalUserId);
        relation.setStatus(RbacConstants.STATUS_ENABLED);
        relation.setIsDefault(makeDefault ? RbacConstants.IS_DEFAULT_YES : 0);
        if (makeDefault) {
            for (SysUserSystem row : existingRows) {
                if (!row.getSystemId().equals(request.systemId()) && isDefault(row)) {
                    row.setIsDefault(0);
                    userSystemRepository.save(row);
                }
            }
        }
        SysUserSystem saved = userSystemRepository.save(relation);
        return new UserSystemVO(saved.getId(), saved.getSystemId(), system.getCode(),
                saved.getExternalUserId(), isDefault(saved), saved.getStatus());
    }

    @Transactional
    public void disable(Long id) {
        SysUserSystem relation = userSystemRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("user system not found: " + id));
        relation.setStatus(RbacConstants.STATUS_DISABLED);
        userSystemRepository.save(relation);
    }

    /**
     * GET /api/me/systems（契约 §3.1）：平台超管返回全部启用系统，其他用户返回已开通且启用的系统。
     */
    @Transactional(readOnly = true)
    public List<AccessedSystemVO> meSystems(String externalUserId) {
        if (permissionQueryService.isPlatformAdmin(externalUserId)) {
            List<SysSystem> enabledSystems =
                    systemRepository.findAllByStatusOrderBySortAscIdAsc(RbacConstants.STATUS_ENABLED);
            Map<Long, SysUserSystem> userSystems = defaultSystems(externalUserId);
            List<AccessedSystemVO> result = new ArrayList<>(enabledSystems.size());
            boolean defaultAssigned = userSystems.values().stream().anyMatch(UserSystemService::isDefault);
            for (int i = 0; i < enabledSystems.size(); i++) {
                SysSystem system = enabledSystems.get(i);
                SysUserSystem relation = userSystems.get(system.getId());
                boolean isDefault = relation != null ? isDefault(relation) : (!defaultAssigned && i == 0);
                result.add(new AccessedSystemVO(system.getId(), system.getCode(), system.getName(),
                        system.getRegion(), isDefault));
            }
            return result;
        }
        List<SysUserSystem> relations = userSystemRepository.findByExternalUserIdAndStatusOrderByIsDefaultDescIdAsc(
                externalUserId, RbacConstants.STATUS_ENABLED);
        Map<Long, SysSystem> systems = loadSystems(relations);
        List<AccessedSystemVO> result = new ArrayList<>(relations.size());
        for (SysUserSystem relation : relations) {
            SysSystem system = systems.get(relation.getSystemId());
            if (system == null || system.getStatus() == null
                    || system.getStatus() != RbacConstants.STATUS_ENABLED) {
                continue;
            }
            result.add(new AccessedSystemVO(system.getId(), system.getCode(), system.getName(),
                    system.getRegion(), isDefault(relation)));
        }
        result.sort(Comparator.comparing(AccessedSystemVO::isDefault).reversed()
                .thenComparing(AccessedSystemVO::id));
        return result;
    }

    /**
     * GET /openapi/sdk/user-systems（契约 §4.2）：返回元素为 {code,name,region,isDefault} 的数组。
     */
    @Transactional(readOnly = true)
    public List<UserSystemItem> openApiUserSystems(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            throw ApiException.badRequest("externalUserId is required");
        }
        List<AccessedSystemVO> systems = meSystems(externalUserId);
        List<UserSystemItem> result = new ArrayList<>(systems.size());
        for (AccessedSystemVO system : systems) {
            result.add(new UserSystemItem(system.code(), system.name(), system.region(), system.isDefault()));
        }
        return result;
    }

    private Map<Long, SysUserSystem> defaultSystems(String externalUserId) {
        Map<Long, SysUserSystem> map = new HashMap<>();
        for (SysUserSystem relation : userSystemRepository.findByExternalUserIdOrderByIdAsc(externalUserId)) {
            map.put(relation.getSystemId(), relation);
        }
        return map;
    }

    private Map<Long, SysSystem> loadSystems(List<SysUserSystem> relations) {
        Map<Long, SysSystem> systems = new HashMap<>();
        List<Long> systemIds = relations.stream().map(SysUserSystem::getSystemId).distinct().toList();
        if (!systemIds.isEmpty()) {
            for (SysSystem system : systemRepository.findAllById(systemIds)) {
                systems.put(system.getId(), system);
            }
        }
        return systems;
    }

    private static boolean isDefault(SysUserSystem relation) {
        return relation.getIsDefault() != null && relation.getIsDefault() == RbacConstants.IS_DEFAULT_YES;
    }
}
