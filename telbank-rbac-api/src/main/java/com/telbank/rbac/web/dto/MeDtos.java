package com.telbank.rbac.web.dto;

/**
 * {@code /api/me} 相关 DTO（契约 §3.1）。
 */
public final class MeDtos {

    private MeDtos() {
    }

    /** GET /api/me。 */
    public record MeVO(String externalUserId, boolean platformAdmin) {
    }

    /** GET /api/me/systems 的元素。 */
    public record AccessedSystemVO(Long id, String code, String name, String region, boolean isDefault) {
    }
}
