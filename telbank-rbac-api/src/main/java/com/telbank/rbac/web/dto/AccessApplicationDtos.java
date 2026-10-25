package com.telbank.rbac.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 业务用户临时权限申请 DTO（申请人侧 + 审批回调）。
 *
 * <p>DTO 时间统一使用 UTC {@link Instant}；实体侧仍为 UTC {@code LocalDateTime}。</p>
 */
public final class AccessApplicationDtos {

    private AccessApplicationDtos() {
    }

    /** 可申请角色中的权限预览项。 */
    public record RolePermissionVO(Long id,
                                   String code,
                                   String name,
                                   Integer type) {
    }

    /** 可申请角色：owned 表示申请人当前已有效持有，前端禁止勾选。 */
    public record RoleOptionVO(Long roleId,
                               String roleCode,
                               String roleName,
                               boolean owned,
                               List<RolePermissionVO> permissions) {
    }

    /** GET /api/access-applications/options：当前系统可申请角色与审批链接模板。 */
    public record OptionsVO(Long systemId,
                            String systemCode,
                            String systemName,
                            String approvalUrlTemplate,
                            List<RoleOptionVO> roles) {
    }

    /** POST /api/access-applications：roleIds 非空、最多 20 个（重复项由 service 层去重）；reason 1–200 字。 */
    public record CreateRequest(@NotEmpty @Size(max = 20) List<Long> roleIds,
                                @NotBlank @Size(max = 200) String reason) {
    }

    /** 申请单中的角色快照（申请时的 role_code/role_name）。 */
    public record RoleVO(Long roleId,
                         String roleCode,
                         String roleName) {
    }

    /** 申请单视图：status 转为 PENDING/APPROVED/REJECTED，时间统一 Instant。 */
    public record ApplicationVO(Long id,
                                String applicationNo,
                                Long systemId,
                                String systemCode,
                                String status,
                                int roleCount,
                                List<RoleVO> roles,
                                String reason,
                                Instant approvedValidFrom,
                                Instant approvedValidUntil,
                                String decisionComment,
                                String reviewedBy,
                                Instant reviewedAt,
                                Instant createdAt,
                                String approvalUrl) {
    }

    /** 审批回调：decision 仅接受 APPROVED/REJECTED，validFrom/validUntil 为可选审批窗口。 */
    public record DecisionRequest(@NotBlank String applicationNo,
                                  @NotBlank String decision,
                                  Instant validFrom,
                                  Instant validUntil,
                                  @Size(max = 128) String reviewer,
                                  @Size(max = 255) String decisionComment) {
    }

    /** 审批回调响应：idempotent=true 表示该申请单此前已按相同结果处理。 */
    public record DecisionResponse(String applicationNo,
                                   String status,
                                   boolean idempotent,
                                   List<Long> grantedRoleIds,
                                   Instant approvedValidFrom,
                                   Instant approvedValidUntil) {
    }
}
