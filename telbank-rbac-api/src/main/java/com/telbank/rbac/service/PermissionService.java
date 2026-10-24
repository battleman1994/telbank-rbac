package com.telbank.rbac.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.AntPatternValidator;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.JsonPayloads;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.repository.SysPermissionRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.security.SystemScopeGuard;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionCreateRequest;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionNodeVO;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionUpdateRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 权限资源管理（契约 §3.4，树形、系统内唯一、Ant/Spring 模板语法校验）。
 */
@Service
public class PermissionService {

    private final SysPermissionRepository permissionRepository;
    private final SysSystemRepository systemRepository;
    private final LocalCacheManager localCacheManager;
    private final ChangeNotifyService changeNotifyService;
    private final JsonMapper jsonMapper;
    private final SystemScopeGuard systemScopeGuard;

    public PermissionService(SysPermissionRepository permissionRepository,
                             SysSystemRepository systemRepository,
                             LocalCacheManager localCacheManager,
                             ChangeNotifyService changeNotifyService,
                             JsonMapper jsonMapper,
                             SystemScopeGuard systemScopeGuard) {
        this.permissionRepository = permissionRepository;
        this.systemRepository = systemRepository;
        this.localCacheManager = localCacheManager;
        this.changeNotifyService = changeNotifyService;
        this.jsonMapper = jsonMapper;
        this.systemScopeGuard = systemScopeGuard;
    }

    @Transactional(readOnly = true)
    public List<PermissionNodeVO> tree(Long systemId) {
        if (systemId == null) {
            throw ApiException.badRequest("systemId is required");
        }
        systemScopeGuard.requireAccess(systemId);
        if (!systemRepository.existsById(systemId)) {
            throw ApiException.notFound("system not found: " + systemId);
        }
        List<SysPermission> all = allPermissions(systemId);
        Map<Long, List<SysPermission>> childrenByParent = new HashMap<>();
        Set<Long> existedIds = new HashSet<>(all.size());
        for (SysPermission permission : all) {
            existedIds.add(permission.getId());
        }
        for (SysPermission permission : all) {
            Long parentId = permission.getParentId();
            if (parentId == null || parentId == 0L || !existedIds.contains(parentId)) {
                childrenByParent.computeIfAbsent(0L, key -> new ArrayList<>()).add(permission);
            } else {
                childrenByParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(permission);
            }
        }
        for (List<SysPermission> siblings : childrenByParent.values()) {
            siblings.sort(Comparator
                    .comparing((SysPermission p) -> p.getSort() == null ? 0 : p.getSort())
                    .thenComparing(SysPermission::getId));
        }
        List<PermissionNodeVO> roots = new ArrayList<>();
        for (SysPermission root : childrenByParent.getOrDefault(0L, List.of())) {
            roots.add(buildNode(root, childrenByParent, new HashSet<>()));
        }
        return roots;
    }

    @Transactional(readOnly = true)
    public PermissionNodeVO get(Long id) {
        SysPermission permission = requireById(id);
        systemScopeGuard.requireAccess(permission.getSystemId());
        return toVO(permission, List.of());
    }

    @Transactional
    public PermissionNodeVO create(PermissionCreateRequest request) {
        systemScopeGuard.requireAccess(request.systemId());
        if (!systemRepository.existsById(request.systemId())) {
            throw ApiException.notFound("system not found: " + request.systemId());
        }
        String code = request.code().trim();
        if (permissionRepository.existsBySystemIdAndCode(request.systemId(), code)) {
            throw ApiException.conflict("permission code already exists in system: " + code);
        }
        validateType(request.type());
        String method = normalizeMethodAndPattern(request.method(), request.apiPattern());
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        validateParent(request.systemId(), parentId, null);

        SysPermission permission = new SysPermission();
        permission.setSystemId(request.systemId());
        permission.setParentId(parentId);
        permission.setType(request.type());
        permission.setCode(code);
        permission.setName(request.name().trim());
        permission.setMethod(method);
        permission.setApiPattern(method == null ? null : request.apiPattern().trim());
        permission.setSort(request.sort() == null ? 0 : request.sort());
        permission.setStatus(request.status() == null ? RbacConstants.STATUS_ENABLED : request.status());
        permission.setIsPublic(request.isPublic() == null ? 0 : request.isPublic());
        permission.setMeta(metaOf(request.meta()));
        SysPermission saved = permissionRepository.save(permission);
        invalidateAndPublish(saved.getSystemId());
        return toVO(saved, List.of());
    }

    @Transactional
    public PermissionNodeVO update(Long id, PermissionUpdateRequest request) {
        SysPermission permission = requireById(id);
        systemScopeGuard.requireAccess(permission.getSystemId());
        String code = request.code().trim();
        if (!code.equals(permission.getCode())
                && permissionRepository.existsBySystemIdAndCodeAndIdNot(permission.getSystemId(), code, id)) {
            throw ApiException.conflict("permission code already exists in system: " + code);
        }
        Long parentId = request.parentId() == null ? permission.getParentId() : request.parentId();
        validateParent(permission.getSystemId(), parentId, id);
        if (request.type() != null) {
            validateType(request.type());
            permission.setType(request.type());
        }
        String method = normalizeMethodAndPattern(request.method(), request.apiPattern());
        permission.setCode(code);
        permission.setName(request.name().trim());
        permission.setParentId(parentId);
        permission.setMethod(method);
        permission.setApiPattern(method == null ? null : request.apiPattern().trim());
        if (request.sort() != null) {
            permission.setSort(request.sort());
        }
        if (request.status() != null) {
            permission.setStatus(request.status());
        }
        if (request.isPublic() != null) {
            permission.setIsPublic(request.isPublic());
        }
        if (request.meta() != null) {
            permission.setMeta(metaOf(request.meta()));
        }
        SysPermission saved = permissionRepository.save(permission);
        invalidateAndPublish(saved.getSystemId());
        return toVO(saved, List.of());
    }

    /**
     * DELETE 为停用；有子节点时 400（契约 §3.4）。
     */
    @Transactional
    public void disable(Long id) {
        SysPermission permission = requireById(id);
        systemScopeGuard.requireAccess(permission.getSystemId());
        if (permissionRepository.existsBySystemIdAndParentId(permission.getSystemId(), id)) {
            throw ApiException.badRequest("permission has children, cannot disable: " + id);
        }
        permission.setStatus(RbacConstants.STATUS_DISABLED);
        permissionRepository.save(permission);
        invalidateAndPublish(permission.getSystemId());
    }

    @Transactional(readOnly = true)
    public SysPermission requireById(Long id) {
        return permissionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("permission not found: " + id));
    }

    public List<SysPermission> allPermissions(Long systemId) {
        return localCacheManager.getPermissions(systemId,
                () -> permissionRepository.findAllBySystemIdOrderBySortAscIdAsc(systemId));
    }

    private PermissionNodeVO buildNode(SysPermission permission,
                                       Map<Long, List<SysPermission>> childrenByParent,
                                       Set<Long> visited) {
        if (!visited.add(permission.getId())) {
            throw ApiException.badRequest("permission tree cycle detected at id: " + permission.getId());
        }
        List<PermissionNodeVO> children = new ArrayList<>();
        for (SysPermission child : childrenByParent.getOrDefault(permission.getId(), List.of())) {
            children.add(buildNode(child, childrenByParent, visited));
        }
        visited.remove(permission.getId());
        return toVO(permission, children);
    }

    private void validateParent(Long systemId, Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (selfId != null && selfId.equals(parentId)) {
            throw ApiException.badRequest("parentId must not be the permission itself");
        }
        SysPermission parent = permissionRepository.findByIdAndSystemId(parentId, systemId)
                .orElseThrow(() -> ApiException.badRequest(
                        "parent permission not found in system: " + parentId));
        if (selfId != null) {
            Long cursor = parent.getParentId();
            Set<Long> visited = new HashSet<>();
            while (cursor != null && cursor != 0L && visited.add(cursor)) {
                if (cursor.equals(selfId)) {
                    throw ApiException.badRequest("permission tree cycle detected");
                }
                cursor = permissionRepository.findById(cursor)
                        .map(SysPermission::getParentId)
                        .orElse(0L);
            }
        }
    }

    private static void validateType(Integer type) {
        if (type == null || type < 1 || type > 3) {
            throw ApiException.badRequest("type must be 1 (directory/page), 2 (action) or 3 (api)");
        }
    }

    private static String normalizeMethodAndPattern(String method, String apiPattern) {
        try {
            return AntPatternValidator.normalizeMethodAndPattern(method, apiPattern);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private String metaOf(tools.jackson.databind.JsonNode meta) {
        if (meta == null) {
            return null;
        }
        try {
            return JsonPayloads.normalize(meta, jsonMapper);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private void invalidateAndPublish(Long systemId) {
        localCacheManager.invalidatePermissions(systemId);
        changeNotifyService.publishAfterCommit(systemId, RbacConstants.CHANGE_TYPE_PERMISSION);
    }

    private PermissionNodeVO toVO(SysPermission permission, List<PermissionNodeVO> children) {
        return new PermissionNodeVO(permission.getId(), permission.getSystemId(), permission.getParentId(),
                permission.getType(), permission.getCode(), permission.getName(), permission.getMethod(),
                permission.getApiPattern(), permission.getSort(), permission.getStatus(),
                permission.getIsPublic(), JsonPayloads.toNode(permission.getMeta(), jsonMapper), children);
    }
}
