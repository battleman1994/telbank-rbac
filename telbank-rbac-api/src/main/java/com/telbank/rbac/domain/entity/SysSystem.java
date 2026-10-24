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
}
