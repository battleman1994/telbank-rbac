package com.telbank.rbac.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 外部用户可访问系统（V1 {@code sys_user_system}，{@code (system_id, external_user_id)} 唯一）。
 */
@Entity
@Table(name = "sys_user_system")
public class SysUserSystem extends BaseEntity {

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "external_user_id", nullable = false, length = 255)
    private String externalUserId;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "is_default", nullable = false)
    private Integer isDefault = 0;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }
}
