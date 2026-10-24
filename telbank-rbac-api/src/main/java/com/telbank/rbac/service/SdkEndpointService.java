package com.telbank.rbac.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.AntPatternValidator;
import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.common.TimeUtils;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysSdkEndpoint;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysPermissionRepository;
import com.telbank.rbac.domain.repository.SysSdkEndpointRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.web.dto.OpenApiDtos.EndpointReport;
import com.telbank.rbac.web.dto.OpenApiDtos.EndpointsSyncRequest;
import com.telbank.rbac.web.dto.OpenApiDtos.EndpointsSyncResponse;
import com.telbank.rbac.web.dto.SdkEndpointDtos.SdkEndpointVO;

/**
 * SDK 端点上报（契约 §3.9 / §4.2 endpoints/sync）。
 */
@Service
public class SdkEndpointService {

    private static final Logger log = LoggerFactory.getLogger(SdkEndpointService.class);

    private static final String SOURCE_GENERATED = "GENERATED";
    private static final String SOURCE_ANNOTATION = "ANNOTATION";

    private final SysSdkEndpointRepository endpointRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysSystemRepository systemRepository;
    private final LocalCacheManager localCacheManager;
    private final ChangeNotifyService changeNotifyService;

    public SdkEndpointService(SysSdkEndpointRepository endpointRepository,
                              SysPermissionRepository permissionRepository,
                              SysSystemRepository systemRepository,
                              LocalCacheManager localCacheManager,
                              ChangeNotifyService changeNotifyService) {
        this.endpointRepository = endpointRepository;
        this.permissionRepository = permissionRepository;
        this.systemRepository = systemRepository;
        this.localCacheManager = localCacheManager;
        this.changeNotifyService = changeNotifyService;
    }

    @Transactional(readOnly = true)
    public PageResult<SdkEndpointVO> page(Long systemId, String keyword, PageQuery pageQuery) {
        if (systemId == null) {
            throw ApiException.badRequest("systemId is required");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<SysSdkEndpoint> page = endpointRepository.search(systemId, normalizedKeyword,
                pageQuery.toPageable());
        Map<Long, String> permissionCodes = loadPermissionCodes(page.getContent());
        List<SdkEndpointVO> items = page.getContent().stream()
                .map(endpoint -> toVO(endpoint,
                        endpoint.getPermissionId() == null ? null : permissionCodes.get(endpoint.getPermissionId())))
                .toList();
        return PageResult.of(items, page.getTotalElements(), pageQuery);
    }

    /**
     * 关联权限码：把 sys_permission.method/api_pattern 更新为该端点的 method/path（契约 §3.9）。
     */
    @Transactional
    public SdkEndpointVO link(Long id, Long permissionId) {
        SysSdkEndpoint endpoint = requireEndpoint(id);
        SysPermission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> ApiException.notFound("permission not found: " + permissionId));
        if (!permission.getSystemId().equals(endpoint.getSystemId())) {
            throw ApiException.badRequest("permission " + permissionId
                    + " does not belong to system " + endpoint.getSystemId());
        }
        String method;
        try {
            method = AntPatternValidator.normalizeMethodAndPattern(endpoint.getMethod(), endpoint.getPath());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
        permission.setMethod(method);
        permission.setApiPattern(endpoint.getPath());
        permissionRepository.save(permission);
        endpoint.setPermissionId(permissionId);
        SysSdkEndpoint saved = endpointRepository.save(endpoint);
        localCacheManager.invalidatePermissions(endpoint.getSystemId());
        changeNotifyService.publishAfterCommit(endpoint.getSystemId(), RbacConstants.CHANGE_TYPE_PERMISSION);
        changeNotifyService.publishAfterCommit(endpoint.getSystemId(), RbacConstants.CHANGE_TYPE_ENDPOINT);
        return toVO(saved, permission.getCode());
    }

    @Transactional
    public SdkEndpointVO unlink(Long id) {
        SysSdkEndpoint endpoint = requireEndpoint(id);
        endpoint.setPermissionId(null);
        SysSdkEndpoint saved = endpointRepository.save(endpoint);
        changeNotifyService.publishAfterCommit(endpoint.getSystemId(), RbacConstants.CHANGE_TYPE_ENDPOINT);
        return toVO(saved, null);
    }

    /**
     * SDK 全量端点上报：按 {@code (system_id, method, path)} 幂等 upsert（契约 §4.2）。
     */
    @Transactional
    public EndpointsSyncResponse sync(EndpointsSyncRequest request) {
        if (request.systemCode() == null || request.systemCode().isBlank()) {
            throw ApiException.badRequest("systemCode is required");
        }
        SysSystem system = systemRepository.findByCode(request.systemCode().trim())
                .orElseThrow(() -> ApiException.notFound("system not found: " + request.systemCode()));
        List<EndpointReport> endpoints = request.endpoints() == null ? List.of() : request.endpoints();
        int created = 0;
        int updated = 0;
        LocalDateTime reportedAt = TimeUtils.nowUtc();
        for (EndpointReport report : endpoints) {
            if (!isValidReport(report)) {
                log.warn("skip invalid sdk endpoint report: systemCode={} report={}",
                        request.systemCode(), report);
                continue;
            }
            String method = report.method().trim().toUpperCase();
            String path = report.path().trim();
            SysSdkEndpoint endpoint = endpointRepository
                    .findBySystemIdAndMethodAndPath(system.getId(), method, path)
                    .orElse(null);
            if (endpoint == null) {
                endpoint = new SysSdkEndpoint();
                endpoint.setSystemId(system.getId());
                endpoint.setMethod(method);
                endpoint.setPath(path);
                created++;
            } else {
                updated++;
            }
            endpoint.setCandidateCode(blankToNull(report.candidateCode()));
            endpoint.setSource(normalizeSource(report.source()));
            endpoint.setReportedAt(reportedAt);
            endpointRepository.save(endpoint);
        }
        if (created + updated > 0) {
            changeNotifyService.publishAfterCommit(system.getId(), RbacConstants.CHANGE_TYPE_ENDPOINT);
        }
        return new EndpointsSyncResponse(endpoints.size(), created, updated);
    }

    private SysSdkEndpoint requireEndpoint(Long id) {
        return endpointRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("sdk endpoint not found: " + id));
    }

    private Map<Long, String> loadPermissionCodes(List<SysSdkEndpoint> endpoints) {
        List<Long> permissionIds = endpoints.stream()
                .map(SysSdkEndpoint::getPermissionId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> codes = new HashMap<>();
        if (!permissionIds.isEmpty()) {
            for (SysPermission permission : permissionRepository.findAllById(permissionIds)) {
                codes.put(permission.getId(), permission.getCode());
            }
        }
        return codes;
    }

    private static boolean isValidReport(EndpointReport report) {
        return report != null
                && report.method() != null && !report.method().isBlank()
                && report.path() != null && !report.path().isBlank()
                && report.method().trim().length() <= 10
                && report.path().trim().length() <= 255;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return SOURCE_GENERATED;
        }
        String normalized = source.trim().toUpperCase();
        return SOURCE_ANNOTATION.equals(normalized) ? SOURCE_ANNOTATION : SOURCE_GENERATED;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static SdkEndpointVO toVO(SysSdkEndpoint endpoint, String permissionCode) {
        return new SdkEndpointVO(endpoint.getId(), endpoint.getSystemId(), endpoint.getMethod(),
                endpoint.getPath(), endpoint.getCandidateCode(), endpoint.getSource(),
                endpoint.getPermissionId(), permissionCode,
                TimeUtils.toInstant(endpoint.getReportedAt()));
    }
}
