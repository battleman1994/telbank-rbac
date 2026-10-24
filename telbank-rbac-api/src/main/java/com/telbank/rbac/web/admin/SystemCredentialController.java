package com.telbank.rbac.web.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.SystemCredentialService;
import com.telbank.rbac.web.dto.SystemCredentialDtos.SystemCredentialVO;

/**
 * 系统接入凭据查看（A18）。
 *
 * <ul>
 *   <li>{@code GET /api/system-credentials/current}：当前 X-System-Code 系统，需
 *       {@code system:credential} 权限（admin 角色的 {@code *} 覆盖；普通角色无该码 403）。</li>
 *   <li>{@code GET /api/system-credentials/{systemId}}：仅平台超管，可跨系统查看任意业务系统。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system-credentials")
public class SystemCredentialController {

    private final SystemCredentialService systemCredentialService;

    public SystemCredentialController(SystemCredentialService systemCredentialService) {
        this.systemCredentialService = systemCredentialService;
    }

    @GetMapping("/current")
    @PreAuthorize("@perm.has('system:credential')")
    public ApiResponse<SystemCredentialVO> current() {
        return ApiResponse.ok(systemCredentialService.current());
    }

    @GetMapping("/{systemId}")
    @PreAuthorize("@perm.isPlatformAdmin()")
    public ApiResponse<SystemCredentialVO> bySystemId(@PathVariable Long systemId) {
        return ApiResponse.ok(systemCredentialService.bySystemId(systemId));
    }
}
