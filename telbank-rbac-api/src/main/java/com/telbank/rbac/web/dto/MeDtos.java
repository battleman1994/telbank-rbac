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

    /** GET /api/me/systems 的元素。 */
    public record AccessedSystemVO(Long id, String code, String name, String region, boolean isDefault) {
    }

    /**
     * GET /api/me/bootstrap：管理端渲染所需的当前用户上下文（契约 §3.1 新增）。
     *
     * <p>{@code permissions} 为当前用户在管理系统（{@code rbac.management-system-code}）内
     * 经时效过滤后的有效权限码；平台超管为 {@code ["*"]}；管理系统缺失时为 {@code []}。</p>
     */
    public record BootstrapVO(String externalUserId,
                              boolean platformAdmin,
                              String managementSystemCode,
                              List<String> permissions,
                              List<AccessedSystemVO> systems) {
    }
}
