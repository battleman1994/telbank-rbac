package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.UserRoleService;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleCreateRequest;
import com.telbank.rbac.web.dto.GrantDtos.UserRoleVO;

import jakarta.validation.Valid;

/**
 * 用户-角色分配（契约 §3.6），需要系统内 {@code role:manage}。
 */
@RestController
@RequestMapping("/api/user-roles")
@PreAuthorize("@perm.has('user:manage')")
public class UserRoleController {

    private final UserRoleService userRoleService;

    public UserRoleController(UserRoleService userRoleService) {
        this.userRoleService = userRoleService;
    }

    @GetMapping
    public ApiResponse<List<UserRoleVO>> list(@RequestParam(required = false) Long systemId,
                                              @RequestParam String externalUserId) {
        return ApiResponse.ok(userRoleService.list(systemId, externalUserId));
    }

    @PostMapping
    public ApiResponse<UserRoleVO> assign(@Valid @RequestBody UserRoleCreateRequest request) {
        return ApiResponse.ok(userRoleService.assign(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        userRoleService.revoke(id);
        return ApiResponse.ok();
    }
}
