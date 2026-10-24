package com.telbank.rbac.web.admin;

import org.springframework.security.access.prepost.PreAuthorize;
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
import com.telbank.rbac.service.SystemService;
import com.telbank.rbac.web.dto.SystemDtos.SystemCreateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemUpdateRequest;
import com.telbank.rbac.web.dto.SystemDtos.SystemVO;

import jakarta.validation.Valid;

/**
 * 系统接入管理（契约 §3.2）：仅平台超管可调用，系统管理员不可见；无删除（PUT 更新 status 启停）。
 */
@RestController
@RequestMapping("/api/systems")
@PreAuthorize("@perm.isPlatformAdmin()")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping
    public ApiResponse<PageResult<SystemVO>> page(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(systemService.page(keyword, PageQuery.of(page, size)));
    }

    @PostMapping
    public ApiResponse<SystemVO> create(@Valid @RequestBody SystemCreateRequest request) {
        return ApiResponse.ok(systemService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SystemVO> update(@PathVariable Long id,
                                        @Valid @RequestBody SystemUpdateRequest request) {
        return ApiResponse.ok(systemService.update(id, request));
    }
}
