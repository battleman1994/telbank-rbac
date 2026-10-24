package com.telbank.rbac.domain.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 权限码统一资源（V1 {@code sys_permission}，{@code (system_id, code)} 唯一）。
 *
 * <p>{@code method}/{@code api_pattern} 同时非空才进入 SDK 映射表做后端接口拦截，
 * 否则该权限码只做前端展示控制（需求 §4.2）。</p>
 */
@Entity
@Table(name = "sys_permission")
public class SysPermission extends BaseEntity {

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "parent_id", nullable = false)
    private Long parentId = 0L;

    /** 1 目录/页面级，2 操作级，3 纯接口。 */
    @Column(name = "type", nullable = false)
    private Integer type;

    @Column(name = "code", nullable = false, length = 128)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "method", length = 10)
    private String method;

    @Column(name = "api_pattern", length = 255)
    private String apiPattern;

    @Column(name = "sort", nullable = false)
    private Integer sort = 0;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "is_public", nullable = false)
    private Integer isPublic = 0;

    /** 扩展元数据，MySQL JSON 列，Java 侧按 JSON 字符串读写。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta")
    private String meta;

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

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

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getApiPattern() {
        return apiPattern;
    }

    public void setApiPattern(String apiPattern) {
        this.apiPattern = apiPattern;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Integer isPublic) {
        this.isPublic = isPublic;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }
}
