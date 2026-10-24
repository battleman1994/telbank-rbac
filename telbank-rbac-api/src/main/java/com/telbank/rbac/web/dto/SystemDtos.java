package com.telbank.rbac.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统接入 DTO（契约 §3.2，仅平台超管）。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    public record SystemVO(Long id,
                           String code,
                           String name,
                           String region,
                           Integer status,
                           Integer sort,
                           String remark,
                           Integer isPlatform,
                           String adminExternalUserId,
                           String adminName,
                           Instant createdAt,
                           Instant updatedAt) {
    }

    /** POST /api/systems：必须同时登记系统管理员。 */
    public record SystemCreateRequest(@NotBlank @Size(max = 64) String code,
                                      @NotBlank @Size(max = 100) String name,
                                      @NotBlank @Size(max = 32) String region,
                                      @NotBlank @Size(max = 255) String adminExternalUserId,
                                      @Size(max = 100) String adminName,
                                      Integer status,
                                      Integer sort,
                                      @Size(max = 255) String remark) {
    }

    /** PUT /api/systems/{id}：管理员账号可选（变更时服务端撤销旧管理员并授权新管理员）。 */
    public record SystemUpdateRequest(@NotBlank @Size(max = 100) String name,
                                      @NotBlank @Size(max = 32) String region,
                                      @Size(max = 255) String adminExternalUserId,
                                      @Size(max = 100) String adminName,
                                      Integer status,
                                      Integer sort,
                                      @Size(max = 255) String remark) {
    }
}
