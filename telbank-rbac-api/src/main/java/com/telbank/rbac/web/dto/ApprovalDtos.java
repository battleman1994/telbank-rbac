package com.telbank.rbac.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审批授权记录 / 即将到期 / 审批回调 DTO（契约 §3.10 / §5）。
 */
public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    public record ApprovalGrantItemVO(String kind,
                                      Long systemId,
                                      String externalUserId,
                                      Long roleId,
                                      Long permissionId,
                                      String sourceApprovalId,
                                      String grantReason,
                                      Instant validFrom,
                                      Instant validUntil,
                                      Integer status,
                                      Instant grantedAt) {
    }

    /** POST /openapi/approval/grant 请求体（契约 §5）。 */
    public record ApprovalGrantRequest(@NotBlank String systemCode,
                                       @NotBlank @Size(max = 255) String externalUserId,
                                       @NotBlank String grantType,
                                       Long roleId,
                                       List<String> permissionCodes,
                                       Instant validFrom,
                                       Instant validUntil,
                                       @NotBlank @Size(max = 128) String approvalId,
                                       @Size(max = 255) String reason) {
    }

    /** 审批回调响应 data。 */
    public record ApprovalGrantResponse(boolean success, Long grantId, boolean idempotent) {
    }
}
