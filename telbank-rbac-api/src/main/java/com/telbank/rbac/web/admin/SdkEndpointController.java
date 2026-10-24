package com.telbank.rbac.web.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.service.SdkEndpointService;
import com.telbank.rbac.web.dto.SdkEndpointDtos.SdkEndpointLinkRequest;
import com.telbank.rbac.web.dto.SdkEndpointDtos.SdkEndpointVO;

import jakarta.validation.Valid;

/**
 * SDK 端点上报列表（契约 §3.9），需要系统内 {@code resource:manage}。
 */
@RestController
@RequestMapping("/api/sdk-endpoints")
@PreAuthorize("@perm.has('resource:manage')")
public class SdkEndpointController {

    private final SdkEndpointService sdkEndpointService;

    public SdkEndpointController(SdkEndpointService sdkEndpointService) {
        this.sdkEndpointService = sdkEndpointService;
    }

    @GetMapping
    public ApiResponse<PageResult<SdkEndpointVO>> page(@RequestParam Long systemId,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(sdkEndpointService.page(systemId, keyword, PageQuery.of(page, size)));
    }

    @PutMapping("/{id}/link")
    public ApiResponse<SdkEndpointVO> link(@PathVariable Long id,
                                           @Valid @RequestBody SdkEndpointLinkRequest request) {
        return ApiResponse.ok(sdkEndpointService.link(id, request.permissionId()));
    }

    @PutMapping("/{id}/unlink")
    public ApiResponse<SdkEndpointVO> unlink(@PathVariable Long id) {
        return ApiResponse.ok(sdkEndpointService.unlink(id));
    }
}
