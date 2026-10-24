package com.telbank.rbac.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用户-系统开通 DTO（契约 §3.7）。
 */
public final class UserSystemDtos {

    private UserSystemDtos() {
    }

    public record UserSystemVO(Long id,
                               Long systemId,
                               String systemCode,
                               String externalUserId,
                               boolean isDefault,
                               Integer status) {
    }

    public record UserSystemCreateRequest(@NotNull Long systemId,
                                          @NotBlank @Size(max = 255) String externalUserId,
                                          Boolean isDefault) {
    }
}
