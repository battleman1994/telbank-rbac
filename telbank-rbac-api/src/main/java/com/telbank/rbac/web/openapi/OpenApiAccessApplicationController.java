package com.telbank.rbac.web.openapi;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.AccessDecisionService;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionRequest;
import com.telbank.rbac.web.dto.AccessApplicationDtos.DecisionResponse;

import jakarta.validation.Valid;

/**
 * 业务用户临时权限申请的审批决策回调（外部审批平台 → 权限中心）。
 *
 * <p>路径前缀 {@code /openapi/approval/**} 由 {@code AppKeyAuthFilter} 使用
 * {@code rbac.approval} 独立 AppKey/AppSecret 验签，本接口不加载 JWT、不加
 * {@code @PreAuthorize}，业务校验全部在 {@link AccessDecisionService} 内完成。</p>
 */
@RestController
@RequestMapping("/openapi/approval/access-applications")
public class OpenApiAccessApplicationController {

    private final AccessDecisionService accessDecisionService;

    public OpenApiAccessApplicationController(AccessDecisionService accessDecisionService) {
        this.accessDecisionService = accessDecisionService;
    }

    @PostMapping("/decision")
    public ApiResponse<DecisionResponse> decide(
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(accessDecisionService.decide(request));
    }
}
