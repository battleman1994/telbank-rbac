package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.security.CurrentUser;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.service.UserAccessService;
import com.telbank.rbac.service.UserBootstrapService;
import com.telbank.rbac.web.dto.MeDtos.AccessedSystemVO;
import com.telbank.rbac.web.dto.MeDtos.BootstrapVO;
import com.telbank.rbac.web.dto.MeDtos.MeVO;

/**
 * 当前用户、可访问系统与 bootstrap（契约 §3.1）。
 *
 * <p>本 Controller 的接口仅要求合法 JWT（在管理上下文过滤器白名单内），不要求 X-System-Code。</p>
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUserProvider currentUserProvider;
    private final UserAccessService userAccessService;
    private final UserBootstrapService userBootstrapService;
    private final ManagementContextService managementContextService;

    public MeController(CurrentUserProvider currentUserProvider,
                        UserAccessService userAccessService,
                        UserBootstrapService userBootstrapService,
                        ManagementContextService managementContextService) {
        this.currentUserProvider = currentUserProvider;
        this.userAccessService = userAccessService;
        this.userBootstrapService = userBootstrapService;
        this.managementContextService = managementContextService;
    }

    @GetMapping
    public ApiResponse<MeVO> me() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(new MeVO(currentUser.externalUserId(), currentUser.platformAdmin()));
    }

    @GetMapping("/systems")
    public ApiResponse<List<AccessedSystemVO>> systems() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(userAccessService.accessedSystems(currentUser.externalUserId()));
    }

    /**
     * 管理前端 bootstrap：当前用户 + 管理权限码 + 可访问系统（仅认证，不要求权限码）。
     */
    @GetMapping("/bootstrap")
    public ApiResponse<BootstrapVO> bootstrap() {
        CurrentUser currentUser = currentUserProvider.require();
        return ApiResponse.ok(userBootstrapService.bootstrap(currentUser.externalUserId(),
                currentUser.platformAdmin(), managementContextService.requestedSystemCode()));
    }
}
