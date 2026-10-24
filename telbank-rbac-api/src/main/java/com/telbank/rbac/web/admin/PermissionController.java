package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.PermissionService;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionCreateRequest;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionNodeVO;
import com.telbank.rbac.web.dto.PermissionDtos.PermissionUpdateRequest;

import jakarta.validation.Valid;

/**
 * 权限资源管理（契约 §3.4），需要系统内 {@code permission:manage}。
 */
@RestController
@RequestMapping("/api/permissions")
@PreAuthorize("@perm.has('permission:manage')")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResponse<List<PermissionNodeVO>> tree(@RequestParam(required = false) Long systemId) {
        return ApiResponse.ok(permissionService.tree(systemId));
    }

    @GetMapping("/{id}")
    public ApiResponse<PermissionNodeVO> get(@PathVariable Long id) {
        return ApiResponse.ok(permissionService.get(id));
    }

    @PostMapping
    public ApiResponse<PermissionNodeVO> create(@Valid @RequestBody PermissionCreateRequest request) {
        return ApiResponse.ok(permissionService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PermissionNodeVO> update(@PathVariable Long id,
                                                @Valid @RequestBody PermissionUpdateRequest request) {
        return ApiResponse.ok(permissionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        permissionService.disable(id);
        return ApiResponse.ok();
    }
}
