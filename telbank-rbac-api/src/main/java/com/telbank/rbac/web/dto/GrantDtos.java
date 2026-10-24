package com.telbank.rbac.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 角色-权限授权、用户-角色分配 DTO（契约 §3.5 / §3.6）。
 */
public final class GrantDtos {

    private GrantDtos() {
    }

    public record RolePermissionGrantVO(Long permissionId,
                                        String code,
                                        Instant validFrom,
                                        Instant validUntil,
                                        Integer status) {
    }

    public record RolePermissionsVO(Long roleId, List<RolePermissionGrantVO> grants) {
    }

    public record RolePermissionGrantRequest(@NotEmpty List<Long> permissionIds,
                                             Instant validFrom,
                                             Instant validUntil,
                                             @Size(max = 255) String grantReason) {
    }

    public record RolePermissionRevokeRequest(@NotEmpty List<Long> permissionIds) {
    }

    public record UserRoleVO(Long id,
                             Long systemId,
                             String externalUserId,
                             Long roleId,
                             String roleCode,
                             String roleName,
                             Instant validFrom,
                             Instant validUntil,
                             Integer status,
                             String grantReason,
                             String sourceApprovalId) {
    }

    public record UserRoleCreateRequest(@NotNull Long systemId,
                                        @NotBlank @Size(max = 255) String externalUserId,
                                        @NotNull Long roleId,
                                        Instant validFrom,
                                        Instant validUntil,
                                        @Size(max = 255) String grantReason) {
    }
}
