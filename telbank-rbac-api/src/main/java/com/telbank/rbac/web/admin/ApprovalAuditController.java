package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.service.ApprovalAuditService;
import com.telbank.rbac.web.dto.ApprovalDtos.ApprovalGrantItemVO;

/**
 * 审批授权记录 / 即将到期授权（契约 §3.10），需要系统内 {@code audit:view}。
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("@perm.has('audit:view')")
public class ApprovalAuditController {

    private final ApprovalAuditService approvalAuditService;

    public ApprovalAuditController(ApprovalAuditService approvalAuditService) {
        this.approvalAuditService = approvalAuditService;
    }

    @GetMapping("/approval-grants")
    public ApiResponse<PageResult<ApprovalGrantItemVO>> approvalGrants(
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String externalUserId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(approvalAuditService.approvalGrants(systemId, externalUserId,
                PageQuery.of(page, size)));
    }

    @GetMapping("/expiring-grants")
    public ApiResponse<List<ApprovalGrantItemVO>> expiringGrants(
            @RequestParam(required = false, defaultValue = "7") Integer withinDays,
            @RequestParam(required = false) Long systemId) {
        return ApiResponse.ok(approvalAuditService.expiringGrants(withinDays, systemId));
    }
}
