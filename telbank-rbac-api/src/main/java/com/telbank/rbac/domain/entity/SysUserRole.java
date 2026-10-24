package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 外部用户-角色关联（V1 {@code sys_user_role}，{@code (system_id, external_user_id, role_id)} 唯一）。
 */
@Entity
@Table(name = "sys_user_role")
public class SysUserRole extends BaseEntity {

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "external_user_id", nullable = false, length = 255)
    private String externalUserId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "auto_revoke", nullable = false)
    private Integer autoRevoke = 1;

    @Column(name = "grant_reason", length = 255)
    private String grantReason;

    @Column(name = "source_approval_id", length = 128)
    private String sourceApprovalId;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDateTime validUntil) {
        this.validUntil = validUntil;
    }

    public Integer getAutoRevoke() {
        return autoRevoke;
    }

    public void setAutoRevoke(Integer autoRevoke) {
        this.autoRevoke = autoRevoke;
    }

    public String getGrantReason() {
        return grantReason;
    }

    public void setGrantReason(String grantReason) {
        this.grantReason = grantReason;
    }

    public String getSourceApprovalId() {
        return sourceApprovalId;
    }

    public void setSourceApprovalId(String sourceApprovalId) {
        this.sourceApprovalId = sourceApprovalId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
