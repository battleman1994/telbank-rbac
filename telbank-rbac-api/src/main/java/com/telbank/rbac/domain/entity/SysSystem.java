package com.telbank.rbac.domain.entity;



import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 业务系统（V1 {@code sys_system}）。
 */

@TableName("sys_system")
public class SysSystem extends BaseEntity {

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    /** 所属区域，决定 Redis 通知频道。 */
    @TableField("region")
    private String region;

    @TableField("status")
    private Integer status = 1;

    @TableField("sort")
    private Integer sort = 0;

    @TableField(value = "remark", updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    /** 1=权限中心平台系统（不参与业务系统选择），0/null=业务系统。 */
    @TableField("is_platform")
    private Integer isPlatform = 0;

    /** 系统管理员外部账号（系统接入时登记）。 */
    @TableField(value = "admin_external_user_id", updateStrategy = FieldStrategy.ALWAYS)
    private String adminExternalUserId;

    /** 系统管理员姓名。 */
    @TableField(value = "admin_name", updateStrategy = FieldStrategy.ALWAYS)
    private String adminName;

    /** 开放接口 AppKey（业务系统接入时分配，全局唯一；platform 为 null）。 */
    @TableField(value = "app_key", updateStrategy = FieldStrategy.ALWAYS)
    private String appKey;

    /** 开放接口 HMAC Secret（仅创建/审批响应返回一次；列表只返回掩码）。 */
    @TableField(value = "app_secret", updateStrategy = FieldStrategy.ALWAYS)
    private String appSecret;

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

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }
}
