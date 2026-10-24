package com.telbank.rbac.domain.entity;



import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 权限码统一资源（V1 {@code sys_permission}，{@code (system_id, code)} 唯一）。
 *
 * <p>{@code method}/{@code api_pattern} 同时非空才进入 SDK 映射表做后端接口拦截，
 * 否则该权限码只做前端展示控制（需求 §4.2）。</p>
 */

@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    @TableField("system_id")
    private Long systemId;

    @TableField("parent_id")
    private Long parentId = 0L;

    /** 1 目录/页面级，2 操作级，3 纯接口。 */
    @TableField("type")
    private Integer type;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField(value = "method", updateStrategy = FieldStrategy.ALWAYS)
    private String method;

    @TableField(value = "api_pattern", updateStrategy = FieldStrategy.ALWAYS)
    private String apiPattern;

    @TableField("sort")
    private Integer sort = 0;

    @TableField("status")
    private Integer status = 1;

    @TableField("is_public")
    private Integer isPublic = 0;

    /** 扩展元数据，MySQL JSON 列，Java 侧按 JSON 字符串读写。 */
    @TableField(value = "meta", updateStrategy = FieldStrategy.ALWAYS)
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
