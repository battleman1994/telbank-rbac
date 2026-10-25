package com.telbank.rbac.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 角色管理 DTO（契约 §3.3）。
 */
public final class RoleDtos {

    private RoleDtos() {
    }

    public record RoleVO(Long id,
                         Long systemId,
                         String code,
                         String name,
                         Integer status,
                         Integer sort,
                         String remark,
                         Long timeTemplateId,
                         String timeTemplateName) {
    }

    public record RoleCreateRequest(@NotNull Long systemId,
                                    @NotBlank @Size(max = 64) String code,
                                    @NotBlank @Size(max = 100) String name,
                                    Integer status,
                                    Integer sort,
                                    @Size(max = 255) String remark,
                                    Long timeTemplateId) {
    }

    public record RoleUpdateRequest(@NotBlank @Size(max = 100) String name,
                                    Integer status,
                                    Integer sort,
                                    @Size(max = 255) String remark,
                                    Long timeTemplateId) {
    }
}
