package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 系统接入申请（A16，{@code sys_system_application}）。
 *
 * <p>SDK 使用 onboarding AppKey 上报后只写本表（status=0 待审核），不直接创建业务系统；
 * 平台超管审核通过后才由现有系统接入逻辑创建 {@code sys_system} 及管理员角色/通配权限。</p>
 */
@TableName("sys_system_application")
public class SysSystemApplication extends BaseEntity {

    @TableField("system_code")
    private String systemCode;

    @TableField("name")
    private String name;

    @TableField("region")
    private String region;

    @TableField("source")
    private String source;

    @TableField(value = "app_key", updateStrategy = FieldStrategy.ALWAYS)
    private String appKey;

    @TableField(value = "sdk_version", updateStrategy = FieldStrategy.ALWAYS)
    private String sdkVersion;

    @TableField(value = "remark", updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    /** 0=待审核(PENDING) 1=已通过(APPROVED) 2=已驳回(REJECTED)。 */
    @TableField("status")
    private Integer status;

    @TableField(value = "reject_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String rejectReason;

    @TableField(value = "reviewed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String reviewedBy;

    @TableField(value = "reviewed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime reviewedAt;

    @TableField(value = "system_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long systemId;

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }
}
