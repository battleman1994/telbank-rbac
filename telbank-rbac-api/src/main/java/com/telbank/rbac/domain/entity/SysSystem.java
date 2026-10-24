package com.telbank.rbac.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 业务系统（V1 {@code sys_system}）。
 */
@Entity
@Table(name = "sys_system")
public class SysSystem extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 所属区域，决定 Redis 通知频道。 */
    @Column(name = "region", nullable = false, length = 32)
    private String region;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "sort", nullable = false)
    private Integer sort = 0;

    @Column(name = "remark", length = 255)
    private String remark;

    /** 1=权限中心平台系统（不参与业务系统选择），0/null=业务系统。 */
    @Column(name = "is_platform", nullable = false)
    private Integer isPlatform = 0;

    /** 系统管理员外部账号（系统接入时登记）。 */
    @Column(name = "admin_external_user_id", length = 255)
    private String adminExternalUserId;

    /** 系统管理员姓名。 */
    @Column(name = "admin_name", length = 100)
    private String adminName;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getIsPlatform() {
        return isPlatform;
    }

    public void setIsPlatform(Integer isPlatform) {
        this.isPlatform = isPlatform;
    }

    public String getAdminExternalUserId() {
        return adminExternalUserId;
    }

    public void setAdminExternalUserId(String adminExternalUserId) {
        this.adminExternalUserId = adminExternalUserId;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }
}
