package com.telbank.rbac.web.openapi;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.ApprovalGrantService;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantRequest;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantResponse;

import jakarta.validation.Valid;

/**
 * 审批系统回调授权接口（契约 §5）。
 *
 * <p>验签使用 {@code rbac.approval} 独立 AppKey/AppSecret，由
 * {@code AppKeyAuthFilter} 统一处理；本接口只做业务处理。</p>
 */
@RestController
@RequestMapping("/openapi/approval")
public class OpenApiApprovalController {

    private final ApprovalGrantService approvalGrantService;

    public OpenApiApprovalController(ApprovalGrantService approvalGrantService) {
        this.approvalGrantService = approvalGrantService;
    }

    @PostMapping("/grant")
    public ApiResponse<ApprovalGrantResponse> grant(@Valid @RequestBody ApprovalGrantRequest request) {
        return ApiResponse.ok(approvalGrantService.grant(request));
    }
}
