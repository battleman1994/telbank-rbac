package com.telbank.rbac.web.admin;

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
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.service.RoleService;
import com.telbank.rbac.web.dto.RoleDtos.RoleCreateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleUpdateRequest;
import com.telbank.rbac.web.dto.RoleDtos.RoleVO;

import jakarta.validation.Valid;

/**
 * 角色管理（契约 §3.3），需要系统内 {@code role:manage}。
 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize("@perm.has('role:manage')")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ApiResponse<PageResult<RoleVO>> page(@RequestParam Long systemId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(roleService.page(systemId, keyword, PageQuery.of(page, size)));
    }

    @PostMapping
    public ApiResponse<RoleVO> create(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<RoleVO> update(@PathVariable Long id,
                                      @Valid @RequestBody RoleUpdateRequest request) {
        return ApiResponse.ok(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        roleService.disable(id);
        return ApiResponse.ok();
    }
}
