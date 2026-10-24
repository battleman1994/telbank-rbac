package com.telbank.rbac.common;

import java.util.Set;

/**
 * 权限码通配匹配器（A 方案，后端唯一匹配口径）。
 *
 * <p>匹配规则：</p>
 * <ul>
 *   <li>{@code *}：匹配任意权限码；</li>
 *   <li>{@code 前缀:*}（如 {@code order:*}）：匹配以 {@code 前缀:} 开头的权限码；</li>
 *   <li>其余按精确匹配。</li>
 * </ul>
 *
 * <p>多个 required 按 AND 语义逐个匹配（{@link #matchesAll}），或按 OR 语义
 * （{@link #matchesAny}）。该口径与前端 hasPerm、SDK 拦截器保持一致。</p>
 */
public final class PermissionMatcher {

    /** 通配权限码：匹配任意权限。 */
    public static final String WILDCARD = "*";

    /** 前缀通配后缀。 */
    public static final String PREFIX_SUFFIX = ":*";

    private PermissionMatcher() {
    }

    /**
     * 单个 required 是否被 granted 集合覆盖。
     */
    public static boolean matches(Set<String> granted, String required) {
        if (granted == null || granted.isEmpty() || required == null || required.isBlank()) {
            return false;
        }
        if (granted.contains(WILDCARD) || granted.contains(required)) {
            return true;
        }
        for (String pattern : granted) {
            if (isPrefixWildcard(pattern) && required.startsWith(prefixOf(pattern))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 多个 required 是否全部被 granted 覆盖（AND 语义）；空数组返回 false。
     */
    public static boolean matchesAll(Set<String> granted, String... required) {
        if (required == null || required.length == 0) {
            return false;
        }
        for (String code : required) {
            if (!matches(granted, code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 多个 required 中是否任意一个被 granted 覆盖（OR 语义）；空数组返回 false。
     */
    public static boolean matchesAny(Set<String> granted, String... required) {
        if (required == null || required.length == 0) {
            return false;
        }
        for (String code : required) {
            if (matches(granted, code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否为前缀通配（{@code 前缀:*}，如 {@code order:*}）。
     */
    public static boolean isPrefixWildcard(String code) {
        return code != null
                && code.endsWith(PREFIX_SUFFIX)
                && code.length() > PREFIX_SUFFIX.length();
    }

    /**
     * 前缀通配的匹配前缀（保留冒号）：{@code order:*} -> {@code order:}，
     * 以便只匹配 {@code order:xxx} 命名空间而不误匹配 {@code orders:xxx}。
     */
    private static String prefixOf(String pattern) {
        return pattern.substring(0, pattern.length() - 1);
    }
}
