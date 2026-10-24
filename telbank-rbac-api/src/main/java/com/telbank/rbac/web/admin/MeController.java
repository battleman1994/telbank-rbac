package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.security.CurrentUser;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.service.UserBootstrapService;
import com.telbank.rbac.service.UserSystemService;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;
import com.telbank.rbac.web.dto.MeDtos.MeVO;

/**
 * 当前用户与可访问系统（契约 §3.1）。仅要求合法 JWT，不校验权限码。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUserProvider currentUserProvider;
    private final UserSystemService userSystemService;
    private final UserBootstrapService userBootstrapService;

    public MeController(CurrentUserProvider currentUserProvider,
                        UserSystemService userSystemService,
                        UserBootstrapService userBootstrapService) {
        this.currentUserProvider = currentUserProvider;
        this.userSystemService = userSystemService;
        this.userBootstrapService = userBootstrapService;
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

    /**
     * 管理前端 bootstrap：当前用户 + 管理权限码 + 可访问系统（仅认证，不要求权限码）。
     */
    @GetMapping("/bootstrap")
    public ApiResponse<BootstrapVO> bootstrap() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(userBootstrapService.bootstrap(
                currentUser.externalUserId(), currentUser.platformAdmin()));
    }
}
