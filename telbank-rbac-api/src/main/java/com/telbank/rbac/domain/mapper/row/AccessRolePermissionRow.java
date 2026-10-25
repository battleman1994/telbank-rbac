package com.telbank.rbac.domain.mapper.row;

/**
 * 角色-有效权限查询行：用于申请人「可申请角色」的权限预览（原生 SQL 联表查询）。
 *
 * <p>使用普通 JavaBean 而非 record，保证 MyBatis 属性映射的确定性。</p>
 */
public class AccessRolePermissionRow {

    private Long roleId;
    private Long id;
    private String code;
    private String name;
    private Integer type;

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }
}
