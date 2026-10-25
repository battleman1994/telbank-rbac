package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 申请单角色快照（{@code sys_access_application_role}）。
 *
 * <p>创建申请时记录角色编码/名称/时间模板快照用于展示；审批时仍以当前 {@code sys_role} 数据校验。</p>
 */
@TableName("sys_access_application_role")
public class SysAccessApplicationRole extends BaseEntity {

    @TableField("application_id")
    private Long applicationId;

    @TableField("role_id")
    private Long roleId;

    @TableField("role_code")
    private String roleCode;

    @TableField("role_name")
    private String roleName;

    @TableField("time_template_id")
    private Long timeTemplateId;

    @TableField(value = "time_template_name", updateStrategy = FieldStrategy.ALWAYS)
    private String timeTemplateName;

    @TableField(value = "approved_valid_from", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime approvedValidFrom;

    @TableField(value = "approved_valid_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime approvedValidUntil;

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public Long getTimeTemplateId() {
        return timeTemplateId;
    }

    public void setTimeTemplateId(Long timeTemplateId) {
        this.timeTemplateId = timeTemplateId;
    }

    public String getTimeTemplateName() {
        return timeTemplateName;
    }

    public void setTimeTemplateName(String timeTemplateName) {
        this.timeTemplateName = timeTemplateName;
    }

    public LocalDateTime getApprovedValidFrom() {
        return approvedValidFrom;
    }

    public void setApprovedValidFrom(LocalDateTime approvedValidFrom) {
        this.approvedValidFrom = approvedValidFrom;
    }

    public LocalDateTime getApprovedValidUntil() {
        return approvedValidUntil;
    }

    public void setApprovedValidUntil(LocalDateTime approvedValidUntil) {
        this.approvedValidUntil = approvedValidUntil;
    }
}
