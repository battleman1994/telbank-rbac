package com.telbank.rbac.sdk.util;

import java.util.Collection;

/**
 * 权限码通配匹配器（A 方案，Java 8 兼容；SDK 内部使用）。
 *
 * <p>匹配口径（与后端 {@code @perm}、前端 {@code hasPerm} 统一）：</p>
 * <ul>
 *   <li><b>精确匹配</b>：{@code granted.equals(required)}；</li>
 *   <li><b>全量通配</b>：granted 为 {@code *} 时匹配任意权限码（平台超管/系统 admin 在 A 方案下均只授一条
 *       {@code *}）；</li>
 *   <li><b>前缀通配</b>：granted 形如 {@code 前缀:*}（如 {@code order:*}），匹配以 {@code 前缀:} 开头的权限码
 *       （如 {@code order:delete}、{@code order:view:amount}）。</li>
 * </ul>
 *
 * <p>权限码区分大小写；{@code null}/空串一律不匹配。</p>
 */
public final class RbacPermissionMatcher {

    /** 全量通配权限码。 */
    public static final String WILDCARD_ALL = "*";

    /** 前缀通配后缀。 */
    private static final String PREFIX_WILDCARD_SUFFIX = ":*";

    private RbacPermissionMatcher() {
    }

    /**
     * 单条已授予权限是否满足所需权限码。
     *
     * @param grantedPermission 用户已授予的权限码（可能是精确码、{@code *} 或 {@code 前缀:*}）
     * @param requiredCode      接口/按钮需要的权限码
     * @return 是否匹配
     */
    public static boolean matches(String grantedPermission, String requiredCode) {
        if (grantedPermission == null || grantedPermission.trim().isEmpty()
                || requiredCode == null || requiredCode.trim().isEmpty()) {
            return false;
        }
        if (WILDCARD_ALL.equals(grantedPermission)) {
            return true;
        }
        if (grantedPermission.equals(requiredCode)) {
            return true;
        }
        if (grantedPermission.endsWith(PREFIX_WILDCARD_SUFFIX)
                && grantedPermission.length() > PREFIX_WILDCARD_SUFFIX.length()) {
            String prefix = grantedPermission.substring(0,
                    grantedPermission.length() - PREFIX_WILDCARD_SUFFIX.length());
            return requiredCode.startsWith(prefix + ":");
        }
        return false;
    }

    /**
     * 用户权限集合中是否有任意一条满足所需权限码（精确 / {@code *} / {@code 前缀:*}）。
     *
     * @param grantedPermissions 用户已授予权限码集合，可为 null/空
     * @param requiredCode       接口/按钮需要的权限码
     * @return 是否匹配
     */
    public static boolean matchesAny(Collection<String> grantedPermissions, String requiredCode) {
        if (grantedPermissions == null || grantedPermissions.isEmpty()
                || requiredCode == null || requiredCode.trim().isEmpty()) {
            return false;
        }
        for (String grantedPermission : grantedPermissions) {
            if (matches(grantedPermission, requiredCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 权限集合中是否包含全量通配 {@code *}（仅精确 {@code *}，前缀通配不算）。
     *
     * <p>用于 {@code /rbac/me/bootstrap} 的 {@code platformAdmin} 判定：A 方案下平台超管与系统 admin
     * 均下发 {@code *}，SDK 以「本系统内拥有全部权限」口径返回。</p>
     */
    public static boolean hasWildcardAll(Collection<String> grantedPermissions) {
        if (grantedPermissions == null || grantedPermissions.isEmpty()) {
            return false;
        }
        for (String grantedPermission : grantedPermissions) {
            if (WILDCARD_ALL.equals(grantedPermission)) {
                return true;
            }
        }
        return false;
    }
}
