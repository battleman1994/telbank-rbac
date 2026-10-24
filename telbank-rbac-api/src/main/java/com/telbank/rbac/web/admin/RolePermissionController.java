package com.telbank.rbac.web.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.RolePermissionService;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionGrantRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionRevokeRequest;
import com.telbank.rbac.web.dto.GrantDtos.RolePermissionsVO;

import jakarta.validation.Valid;

/**
 * 角色-权限授权（契约 §3.5），需要系统内 {@code role:manage}。
 */
@RestController
@RequestMapping("/api/roles/{roleId}/permissions")
@PreAuthorize("@perm.has('role:manage')")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @GetMapping
    public ApiResponse<RolePermissionsVO> grants(@PathVariable Long roleId) {
        return ApiResponse.ok(rolePermissionService.grants(roleId));
    }

    @PostMapping("/grant")
    public ApiResponse<RolePermissionsVO> grant(@PathVariable Long roleId,
                                                @Valid @RequestBody RolePermissionGrantRequest request) {
        return ApiResponse.ok(rolePermissionService.grant(roleId, request));
    }

    @PostMapping("/revoke")
    public ApiResponse<RolePermissionsVO> revoke(@PathVariable Long roleId,
                                                 @Valid @RequestBody RolePermissionRevokeRequest request) {
        return ApiResponse.ok(rolePermissionService.revoke(roleId, request));
    }
}
