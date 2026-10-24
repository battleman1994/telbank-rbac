package com.telbank.rbac.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统接入申请审核 DTO（A16，管理端，仅平台超管）。
 */
public final class SystemApplicationDtos {

    private SystemApplicationDtos() {
    }

    /** 申请单视图：status 转为 PENDING/APPROVED/REJECTED。 */
    public record SystemApplicationVO(Long id,
                                      String systemCode,
                                      String name,
                                      String region,
                                      String source,
                                      String appKey,
                                      String sdkVersion,
                                      String remark,
                                      String status,
                                      String rejectReason,
                                      String reviewedBy,
                                      Instant reviewedAt,
                                      Long systemId,
                                      Instant createdAt) {
    }

    /** POST /api/system-applications/{id}/approve：审核通过并为新系统指定管理员。 */
    public record ApproveRequest(@NotBlank @Size(max = 255) String adminExternalUserId,
                                 @Size(max = 100) String adminName) {
    }

    /**
     * approve 响应：申请单最新状态（含 id/systemId/reviewed_*）+ 新系统接入凭据。
     * 完整 appSecret 仅本次返回（列表/详情只返回掩码）；applicationId 与 id 相同，便于 SDK/旧调用方。
     */
    public record ApproveResponse(Long id,
                                  Long applicationId,
                                  String systemCode,
                                  String name,
                                  String region,
                                  String source,
                                  String appKey,
                                  String sdkVersion,
                                  String remark,
                                  String status,
                                  String rejectReason,
                                  String reviewedBy,
                                  Instant reviewedAt,
                                  Long systemId,
                                  Instant createdAt,
                                  String appSecret) {
    }

    /** POST /api/system-applications/{id}/reject：驳回原因。 */
    public record RejectRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
