package com.telbank.rbac.common;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ant / Spring 风格接口路径校验（契约 §3.4 与 §7.7 统一口径）。
 *
 * <p>允许的语法：</p>
 * <ul>
 *   <li>必须以 {@code /} 开头；</li>
 *   <li>{@code *} 与 Spring 模板 {@code {id}} 都是单段通配，可出现在任意段；</li>
 *   <li>{@code **} 只能独占一个路径段且只能出现在末尾（{@code /api/order/**}）；</li>
 *   <li>不允许出现 {@code **} 与其他字符混排的段（如 {@code /api/**x}）。</li>
 * </ul>
 */
public final class AntPatternValidator {

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^{}/]+}");

    private AntPatternValidator() {
    }

    /**
     * 校验 apiPattern 语法。
     *
     * @throws IllegalArgumentException 语法非法（消息可直接返回给调用方）
     */
    public static void validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("apiPattern must not be blank");
        }
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException("apiPattern must start with '/'");
        }
        String[] segments = pattern.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.contains("**")) {
                if (!"**".equals(segment)) {
                    throw new IllegalArgumentException(
                            "apiPattern '**' must occupy a whole path segment: " + pattern);
                }
                if (i != segments.length - 1) {
                    throw new IllegalArgumentException(
                            "apiPattern '**' is only allowed as the last path segment: " + pattern);
                }
                continue;
            }
            validateTemplateBraces(segment, pattern);
        }
    }

    /**
     * 校验 method/apiPattern 组合：必须同时为空或同时非空；method 会转为大写。
     *
     * @return 规范化后的 method（可能为 null）
     * @throws IllegalArgumentException 组合或 method 非法
     */
    public static String normalizeMethodAndPattern(String method, String pattern) {
        boolean hasMethod = method != null && !method.isBlank();
        boolean hasPattern = pattern != null && !pattern.isBlank();
        if (hasMethod != hasPattern) {
            throw new IllegalArgumentException(
                    "method and apiPattern must be configured together (both empty or both present)");
        }
        if (!hasMethod) {
            return null;
        }
        String normalizedMethod = method.trim().toUpperCase(Locale.ROOT);
        if (normalizedMethod.length() > 10) {
            throw new IllegalArgumentException("method must not exceed 10 characters");
        }
        if (!normalizedMethod.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException("method must contain only A-Z letters");
        }
        validatePattern(pattern.trim());
        return normalizedMethod;
    }

    /**
     * 单段内 Spring 模板变量 {@code {name}} 的花括号必须成对且变量名非空。
     */
    private static void validateTemplateBraces(String segment, String pattern) {
        int open = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '{') {
                open++;
            } else if (c == '}') {
                open--;
                if (open < 0) {
                    throw new IllegalArgumentException("unbalanced braces in apiPattern: " + pattern);
                }
            }
        }
        if (open != 0) {
            throw new IllegalArgumentException("unbalanced braces in apiPattern: " + pattern);
        }
        // 去掉所有合法模板变量后，不能再残留花括号
        String stripped = TEMPLATE_VARIABLE.matcher(segment).replaceAll("");
        if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
            throw new IllegalArgumentException("invalid template variable in apiPattern: " + pattern);
        }
    }

    /**
     * 判断 pattern 是否可以收录进映射表（供测试与内部复用的宽松版本）。
     */
    public static boolean isValid(String pattern) {
        try {
            validatePattern(pattern);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 已知 http method 集合（映射表下发时按原样返回，仅用于宽松校验）。
     */
    public static final Set<String> KNOWN_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE");
}
