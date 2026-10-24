package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.security.CurrentUser;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.service.UserSystemService;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.MeVO;

/**
 * 当前用户与可访问系统（契约 §3.1）。仅要求合法 JWT，不校验权限码。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUserProvider currentUserProvider;
    private final UserSystemService userSystemService;

    public MeController(CurrentUserProvider currentUserProvider, UserSystemService userSystemService) {
        this.currentUserProvider = currentUserProvider;
        this.userSystemService = userSystemService;
    }

    @GetMapping
    public ApiResponse<MeVO> me() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(new MeVO(currentUser.externalUserId(), currentUser.platformAdmin()));
    }

    @GetMapping("/systems")
    public ApiResponse<List<AccessedSystemVO>> systems() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(userSystemService.meSystems(currentUser.externalUserId()));
    }
}
