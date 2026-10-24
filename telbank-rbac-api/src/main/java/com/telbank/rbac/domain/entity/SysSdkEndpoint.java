package com.telbank.rbac.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * SDK 端点上报（V1 {@code sys_sdk_endpoint}，契约 §9.2 新增的第 8 张表）。
 *
 * <p>按 {@code (system_id, method, path)} 幂等 upsert；管理员可关联权限码。</p>
 */
@Entity
@Table(name = "sys_sdk_endpoint")
public class SysSdkEndpoint extends BaseEntity {

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "candidate_code", length = 128)
    private String candidateCode;

    /** GENERATED / ANNOTATION。 */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "GENERATED";

    @Column(name = "permission_id")
    private Long permissionId;

    @Column(name = "reported_at")
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
