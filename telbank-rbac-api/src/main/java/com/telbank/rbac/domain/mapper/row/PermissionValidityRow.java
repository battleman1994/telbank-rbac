package com.telbank.rbac.domain.mapper.row;

/**
 * 用户有效权限码 + 时效包络查询行（原生 SQL，时间字段为 JDBC 驱动返回的原生类型（LocalDateTime/Timestamp/String），由服务层统一转 UTC）。
 */
public class PermissionValidityRow {

    private String code;
    private Object validFrom;
    private Object validUntil;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Object getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Object validFrom) {
        this.validFrom = validFrom;
    }

    public Object getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Object validUntil) {
        this.validUntil = validUntil;
    }
}
