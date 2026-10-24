package com.telbank.rbac.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * SDK 端点上报列表 DTO（契约 §3.9）。
 */
public final class SdkEndpointDtos {

    private SdkEndpointDtos() {
    }

    public record SdkEndpointVO(Long id,
                                Long systemId,
                                String method,
                                String path,
                                String candidateCode,
                                String source,
                                Long permissionId,
                                String permissionCode,
                                Instant reportedAt) {
    }

    public record SdkEndpointLinkRequest(@NotNull Long permissionId) {
    }
}
