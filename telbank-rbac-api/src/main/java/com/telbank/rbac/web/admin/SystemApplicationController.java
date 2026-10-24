package com.telbank.rbac.web.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.service.SystemApplicationService;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.ApproveResponse;
import com.telbank.rbac.web.dto.SystemApplicationDtos.RejectRequest;
import com.telbank.rbac.web.dto.SystemApplicationDtos.SystemApplicationVO;

import jakarta.validation.Valid;

/**
 * 系统接入申请审核（A16/A17）：仅平台超管。approve 直接复用系统接入逻辑创建系统并返回凭据。
 */
@RestController
@RequestMapping("/api/system-applications")
@PreAuthorize("@perm.isPlatformAdmin()")
public class SystemApplicationController {

    private final SystemApplicationService systemApplicationService;
    private final CurrentUserProvider currentUserProvider;

    public SystemApplicationController(SystemApplicationService systemApplicationService,
                                      CurrentUserProvider currentUserProvider) {
        this.systemApplicationService = systemApplicationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<PageResult<SystemApplicationVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(systemApplicationService.page(status, PageQuery.of(page, size)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ApproveResponse> approve(@PathVariable Long id,
                                                @Valid @RequestBody ApproveRequest request) {
        return ApiResponse.ok(systemApplicationService.approve(id, request,
                currentUserProvider.requireExternalUserId()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<SystemApplicationVO> reject(@PathVariable Long id,
                                                   @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(systemApplicationService.reject(id, request,
                currentUserProvider.requireExternalUserId()));
    }
}
