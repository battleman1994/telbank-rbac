package com.telbank.rbac.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * 页面操作元数据 DTO（契约 §3.8，模式 B）。
 *
 * <p>{@code config} 为 MySQL JSON 列：请求接受 JSON 对象或 JSON 字符串，
 * 响应按契约 §4.2 统一返回 JSON 字符串。</p>
 */
public final class PageActionDtos {

    private PageActionDtos() {
    }

    public record PageActionVO(Long id,
                               Long systemId,
                               String pageCode,
                               String code,
                               String text,
                               String type,
                               String action,
                               String config,
                               Integer sort,
                               Integer status) {
    }

    public record PageActionCreateRequest(@NotNull Long systemId,
                                          @NotBlank @Size(max = 128) String pageCode,
                                          @NotBlank @Size(max = 128) String code,
                                          @NotBlank @Size(max = 64) String text,
                                          @Size(max = 32) String type,
                                          @NotBlank @Size(max = 32) String action,
                                          JsonNode config,
                                          Integer sort,
                                          Integer status) {
    }

    public record PageActionUpdateRequest(@NotBlank @Size(max = 128) String pageCode,
                                          @NotBlank @Size(max = 128) String code,
                                          @NotBlank @Size(max = 64) String text,
                                          @Size(max = 32) String type,
                                          @NotBlank @Size(max = 32) String action,
                                          JsonNode config,
                                          Integer sort,
                                          Integer status) {
    }
}
