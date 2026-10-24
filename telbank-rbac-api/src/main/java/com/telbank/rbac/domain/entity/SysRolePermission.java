package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;


/**
 * 角色-权限关联（V1 {@code sys_role_permission}，{@code (role_id, permission_id)} 唯一）。
 */
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_role_permission")
public class SysRolePermission extends BaseEntity {

    @TableField("system_id")
    private Long systemId;

    @TableField("role_id")
    private Long roleId;

    @TableField("permission_id")
    private Long permissionId;

    @TableField(value = "valid_from", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime validFrom;

    @TableField(value = "valid_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime validUntil;

    @TableField("auto_revoke")
    private Integer autoRevoke = 1;

    @TableField(value = "grant_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String grantReason;

    @TableField(value = "source_approval_id", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceApprovalId;

    @TableField("status")
    private Integer status = 1;

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(Long permissionId) {
        this.permissionId = permissionId;
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
