package com.telbank.rbac.domain.entity;



import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 页面操作元数据（V1 {@code sys_page_action}，模式 B，{@code (system_id, page_code, code)} 唯一）。
 */

@TableName("sys_page_action")
public class SysPageAction extends BaseEntity {

    @TableField("system_id")
    private Long systemId;

    @TableField("page_code")
    private String pageCode;

    @TableField("code")
    private String code;

    @TableField("text")
    private String text;

    @TableField("type")
    private String type = "default";

    /** openDialog / callApi / download / navigate / confirmThenCallApi。 */
    @TableField("action")
    private String action;

    /** MySQL JSON 列，按 JSON 字符串读写。 */
    @TableField(value = "config", updateStrategy = FieldStrategy.ALWAYS)
    private String config;

    @TableField("sort")
    private Integer sort = 0;

    @TableField("status")
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
