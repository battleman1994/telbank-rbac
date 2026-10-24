package com.telbank.rbac.web.dto;

import java.util.List;

/**
 * {@code /api/me} 相关 DTO（契约 §3.1）。
 */
public final class MeDtos {

    private MeDtos() {
    }

    /** GET /api/me。 */
    public record MeVO(String externalUserId, boolean platformAdmin) {
    }

    /** GET /api/me/systems 的元素（由有效角色推导）。 */
    public record AccessedSystemVO(Long id, String code, String name, String region, boolean isDefault) {
    }

    /**
     * GET /api/me/bootstrap：管理端渲染所需的当前用户上下文。
     *
     * <p>{@code currentSystemCode} 为请求头 X-System-Code（合法时）否则第一个可访问系统；
     * {@code permissions} 为当前系统内经过时效过滤的权限码集合（平台超管 {@code ["*"]}，
     * 无系统时为空数组）；{@code systems} 由有效角色推导。</p>
     */
    public record BootstrapVO(String externalUserId,
                              boolean platformAdmin,
                              String currentSystemCode,
                              List<String> permissions,
                              List<AccessedSystemVO> systems) {
    }
}
