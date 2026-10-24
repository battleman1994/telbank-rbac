package com.telbank.rbac.sdk.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 路径模式匹配器（SDK 内部，与权限中心 {@code apiPattern} 口径一致，契约 §7.7/§7.8）。
 *
 * <p>匹配优先级由调用方保证：字面量精确 &gt; 单段通配（{@code *} 或 {@code {var}}，等价）
 * &gt; 末尾 {@code **}（匹配剩余任意段，可为 0 段）。</p>
 *
 * <p>非法模式：{@code **} 出现在非末尾（返回 null，由调用方跳过并告警）。</p>
 */
public final class RbacPathMatcher {

    private RbacPathMatcher() {
    }

    /**
     * 归一化路径：去掉 query/fragment，折叠重复斜杠，去掉末尾斜杠；根路径统一为 "/"。
     */
    public static String normalize(String path) {
        if (path == null) {
            return "/";
        }
        String value = path.trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }
        List<String> segments = splitSegments(value);
        if (segments.isEmpty()) {
            return "/";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (String segment : segments) {
            builder.append('/').append(segment);
        }
        return builder.toString();
    }

    /**
     * 编译路径模式；非法模式返回 null。
     */
    public static Compiled compile(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(pattern);
        List<String> segments = splitSegments(normalized);
        boolean trailingDoubleStar = false;
        for (int i = 0; i < segments.size(); i++) {
            if ("**".equals(segments.get(i))) {
                if (i != segments.size() - 1) {
                    return null;
                }
                trailingDoubleStar = true;
            }
        }
        int wildcardSegments = 0;
        int prefixCount = trailingDoubleStar ? segments.size() - 1 : segments.size();
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < prefixCount; i++) {
            String segment = segments.get(i);
            if (!isLiteralSegment(segment)) {
                wildcardSegments++;
            }
            regex.append('/').append(segmentRegex(segment));
        }
        if (trailingDoubleStar) {
            wildcardSegments++;
            regex.append("(?:/.*)?");
        }
        if (prefixCount == 0 && !trailingDoubleStar) {
            regex.append('/');
        }
        regex.append('$');
        try {
            return new Compiled(Pattern.compile(regex.toString()), normalized, trailingDoubleStar,
                    wildcardSegments, segments.size());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 便捷匹配（未预编译，仅用于少量配置路径，如 exclude-paths 初始化/测试）。
     */
    public static boolean matches(String pattern, String path) {
        Compiled compiled = compile(pattern);
        return compiled != null && compiled.matches(path);
    }

    /**
     * 是否为字面量模式（不含任何通配符/路径变量）。
     */
    public static boolean isLiteral(String pattern) {
        Compiled compiled = compile(pattern);
        return compiled != null && compiled.getWildcardSegments() == 0;
    }

    private static List<String> splitSegments(String value) {
        List<String> segments = new ArrayList<String>();
        if (value == null) {
            return segments;
        }
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('/', start);
            if (end < 0) {
                end = value.length();
            }
            if (end > start) {
                segments.add(value.substring(start, end));
            }
            start = end + 1;
        }
        return segments;
    }

    private static boolean isLiteralSegment(String segment) {
        if ("*".equals(segment)) {
            return false;
        }
        if (segment.length() >= 2 && segment.charAt(0) == '{'
                && segment.charAt(segment.length() - 1) == '}') {
            return false;
        }
        return segment.indexOf('*') < 0 && segment.indexOf('?') < 0;
    }

    private static String segmentRegex(String segment) {
        if ("*".equals(segment)) {
            return "[^/]+";
        }
        if (segment.length() >= 2 && segment.charAt(0) == '{'
                && segment.charAt(segment.length() - 1) == '}') {
            // {var} 与 * 等价：匹配一段
            return "[^/]+";
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (ch == '*' || ch == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(ch == '*' ? "[^/]*" : "[^/]");
            } else {
                literal.append(ch);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    /**
     * 预编译后的路径模式。
     */
    public static final class Compiled {

        private final Pattern regex;

        private final String normalizedPattern;

        private final boolean trailingDoubleStar;

        private final int wildcardSegments;

        private final int segmentCount;

        Compiled(Pattern regex, String normalizedPattern, boolean trailingDoubleStar, int wildcardSegments,
                int segmentCount) {
            this.regex = regex;
            this.normalizedPattern = normalizedPattern;
            this.trailingDoubleStar = trailingDoubleStar;
            this.wildcardSegments = wildcardSegments;
            this.segmentCount = segmentCount;
        }

        public boolean matches(String path) {
            return regex.matcher(normalize(path)).matches();
        }

        public String getNormalizedPattern() {
            return normalizedPattern;
        }

        public boolean isTrailingDoubleStar() {
            return trailingDoubleStar;
        }

        public int getWildcardSegments() {
            return wildcardSegments;
        }

        public int getSegmentCount() {
            return segmentCount;
        }

        public boolean isLiteral() {
            return wildcardSegments == 0;
        }
    }
}
