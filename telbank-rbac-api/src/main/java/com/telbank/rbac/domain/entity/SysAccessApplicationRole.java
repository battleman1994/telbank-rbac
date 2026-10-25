package com.telbank.rbac.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 申请单角色快照（{@code sys_access_application_role}）。
 *
 * <p>创建申请时记录角色编码/名称快照用于展示；审批时仍以当前 {@code sys_role} 数据校验。</p>
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
}
