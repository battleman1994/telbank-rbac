package com.telbank.rbac.domain.mapper.row;

/**
 * 用户有效权限码 + 时效包络查询行（原生 SQL，时间以 ISO-8601 字符串返回，避免 JDBC 时区二次转换）。
 */
public class PermissionValidityRow {

    private String code;
    private String validFrom;
    private String validUntil;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(String validFrom) {
        this.validFrom = validFrom;
    }

    public String getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(String validUntil) {
        this.validUntil = validUntil;
    }
}
