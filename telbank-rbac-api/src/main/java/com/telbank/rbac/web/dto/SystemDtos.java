package com.telbank.rbac.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统管理 DTO（契约 §3.2）。
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
                           Instant createdAt,
                           Instant updatedAt) {
    }

    public record SystemCreateRequest(@NotBlank @Size(max = 64) String code,
                                      @NotBlank @Size(max = 100) String name,
                                      @NotBlank @Size(max = 32) String region,
                                      Integer status,
                                      Integer sort,
                                      @Size(max = 255) String remark) {
    }

    public record SystemUpdateRequest(@NotBlank @Size(max = 100) String name,
                                      @NotBlank @Size(max = 32) String region,
                                      Integer status,
                                      Integer sort,
                                      @Size(max = 255) String remark) {
    }
}
