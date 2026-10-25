package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 业务用户临时权限申请单（{@code sys_access_application}）。
 *
 * <p>权限中心只保存申请与审批结果，不实现审批流；审批通过后的授权写入现有
 * {@code sys_user_role}。{@code pending_user_key} 是数据库 STORED 生成列，用于保证
 * 「同系统同申请人最多一条待审批」，不在实体中映射。</p>
 */
@TableName("sys_access_application")
public class SysAccessApplication extends BaseEntity {

    @TableField("application_no")
    private String applicationNo;

    @TableField("system_id")
    private Long systemId;

    @TableField("external_user_id")
    private String externalUserId;

    /** 0=待审批(PENDING) 1=已通过(APPROVED) 2=已驳回(REJECTED)。 */
    @TableField("status")
    private Integer status;

    @TableField("reason")
    private String reason;

    @TableField(value = "approved_valid_from", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime approvedValidFrom;

    @TableField(value = "approved_valid_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime approvedValidUntil;

    @TableField(value = "decision_comment", updateStrategy = FieldStrategy.ALWAYS)
    private String decisionComment;

    @TableField(value = "reviewed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String reviewedBy;

    @TableField(value = "reviewed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime reviewedAt;

    public String getApplicationNo() {
        return applicationNo;
    }

    public void setApplicationNo(String applicationNo) {
        this.applicationNo = applicationNo;
    }

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getApprovedValidFrom() {
        return approvedValidFrom;
    }

    public void setApprovedValidFrom(LocalDateTime approvedValidFrom) {
        this.approvedValidFrom = approvedValidFrom;
    }

    public LocalDateTime getApprovedValidUntil() {
        return approvedValidUntil;
    }

    public void setApprovedValidUntil(LocalDateTime approvedValidUntil) {
        this.approvedValidUntil = approvedValidUntil;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
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
}
