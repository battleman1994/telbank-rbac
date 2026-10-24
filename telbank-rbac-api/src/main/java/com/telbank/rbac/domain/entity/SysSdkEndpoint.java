package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;


/**
 * SDK 端点上报（V1 {@code sys_sdk_endpoint}，契约 §9.2 新增的第 8 张表）。
 *
 * <p>按 {@code (system_id, method, path)} 幂等 upsert；管理员可关联权限码。</p>
 */
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_sdk_endpoint")
public class SysSdkEndpoint extends BaseEntity {

    @TableField("system_id")
    private Long systemId;

    @TableField("method")
    private String method;

    @TableField("path")
    private String path;

    @TableField(value = "candidate_code", updateStrategy = FieldStrategy.ALWAYS)
    private String candidateCode;

    /** GENERATED / ANNOTATION。 */
    @TableField("source")
    private String source = "GENERATED";

    @TableField(value = "permission_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long permissionId;

    @TableField(value = "reported_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime reportedAt;

    public Long getSystemId() {
        return systemId;
    }

    public void setSystemId(Long systemId) {
        this.systemId = systemId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCandidateCode() {
        return candidateCode;
    }

    public void setCandidateCode(String candidateCode) {
        this.candidateCode = candidateCode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(Long permissionId) {
        this.permissionId = permissionId;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }
}
