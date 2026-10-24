package com.telbank.rbac.domain.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 页面操作元数据（V1 {@code sys_page_action}，模式 B，{@code (system_id, page_code, code)} 唯一）。
 */
@Entity
@Table(name = "sys_page_action")
public class SysPageAction extends BaseEntity {

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "page_code", nullable = false, length = 128)
    private String pageCode;

    @Column(name = "code", nullable = false, length = 128)
    private String code;

    @Column(name = "text", nullable = false, length = 64)
    private String text;

    @Column(name = "type", nullable = false, length = 32)
    private String type = "default";

    /** openDialog / callApi / download / navigate / confirmThenCallApi。 */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    /** MySQL JSON 列，按 JSON 字符串读写。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config")
    private String config;

    @Column(name = "sort", nullable = false)
    private Integer sort = 0;

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public String getPageCode() {
        return pageCode;
    }

    public void setPageCode(String pageCode) {
        this.pageCode = pageCode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
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
}
