package com.telbank.rbac.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * 权限资源管理 DTO（契约 §3.4，树形）。
 */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    public record PermissionNodeVO(Long id,
                                   Long systemId,
                                   Long parentId,
                                   Integer type,
                                   String code,
                                   String name,
                                   String method,
                                   String apiPattern,
                                   Integer sort,
                                   Integer status,
                                   Integer isPublic,
                                   JsonNode meta,
                                   List<PermissionNodeVO> children) {
    }

    public record PermissionCreateRequest(@NotNull Long systemId,
                                          Long parentId,
                                          @NotNull Integer type,
                                          @NotBlank @Size(max = 128) String code,
                                          @NotBlank @Size(max = 100) String name,
                                          @Size(max = 10) String method,
                                          @Size(max = 255) String apiPattern,
                                          Integer sort,
                                          Integer status,
                                          Integer isPublic,
                                          JsonNode meta) {
    }

    public record PermissionUpdateRequest(Long parentId,
                                          Integer type,
                                          @NotBlank @Size(max = 128) String code,
                                          @NotBlank @Size(max = 100) String name,
                                          @Size(max = 10) String method,
                                          @Size(max = 255) String apiPattern,
                                          Integer sort,
                                          Integer status,
                                          Integer isPublic,
                                          JsonNode meta) {
    }
}
