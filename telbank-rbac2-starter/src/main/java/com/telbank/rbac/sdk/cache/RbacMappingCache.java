package com.telbank.rbac.sdk.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.client.MappingEntry;

/**
 * 接口映射本地缓存（需求 §6.8，契约 §7.1/§7.7）。
 *
 * <p>权限中心下发 {@code [{method, pattern, code}]} 全量映射，SDK 预编译为
 * 「精确匹配表 + 通配规则列表」；匹配优先级：</p>
 * <ol>
 *   <li>字面量精确匹配（method + 归一化 path 完全相等）；</li>
 *   <li>单段通配（{@code *}/{@code {var}}），更少通配段、更长模式优先；</li>
 *   <li>末尾 {@code **}（匹配剩余任意段，可为零段）。</li>
 * </ol>
 *
 * <p>无 TTL，仅由变更通知/兜底轮询全量替换。实现为不可变快照 + 引用替换，读路径无锁。</p>
 */
public class RbacMappingCache {

    private static final Logger log = LoggerFactory.getLogger(RbacMappingCache.class);

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<Snapshot>(Snapshot.empty());

    private volatile boolean loaded;

    /**
     * 全量替换映射表（权限中心不可用时不调用，保留旧快照）。
     */
    public void replace(List<MappingEntry> mappings) {
        Snapshot next = compile(mappings);
        snapshot.set(next);
        loaded = true;
        log.info("RBAC 映射表已刷新: 精确 {} 条, 通配 {} 条, 原始 {} 条",
                next.exactCount, next.wildcardCount, next.rawMappings.size());
    }

    /**
     * 匹配请求。
     *
     * @param httpMethod HTTP 方法（大小写不敏感）
     * @param path       请求路径（可含 servlet context path 已剥离；query 会被忽略）
     * @return 命中的权限码；未命中返回 null（调用方按 unmatched-strategy 处理）
     */
    public String match(String httpMethod, String path) {
        if (path == null) {
            return null;
        }
        return snapshot.get().match(httpMethod, path);
    }

    public int size() {
        Snapshot current = snapshot.get();
        return current.exactCount + current.wildcardCount;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public List<MappingEntry> getMappings() {
        return snapshot.get().rawMappings;
    }

    public void clear() {
        snapshot.set(Snapshot.empty());
        loaded = false;
    }

    private static Snapshot compile(List<MappingEntry> mappings) {
        Map<String, Map<String, String>> exactByMethod = new HashMap<String, Map<String, String>>();
        Map<String, String> exactAnyMethod = new HashMap<String, String>();
        Map<String, List<Rule>> wildcardByMethod = new HashMap<String, List<Rule>>();
        List<Rule> wildcardAnyMethod = new ArrayList<Rule>();
        List<MappingEntry> rawMappings = new ArrayList<MappingEntry>();
        if (mappings != null) {
            rawMappings.addAll(mappings);
        }
        if (mappings != null) {
            for (int index = 0; index < mappings.size(); index++) {
                MappingEntry entry = mappings.get(index);
                if (entry == null) {
                    continue;
                }
                String code = trimToNull(entry.getCode());
                String pattern = trimToNull(entry.getPattern());
                if (code == null || pattern == null) {
                    log.debug("忽略缺少 code/pattern 的映射条目: {}", entry);
                    continue;
                }
                RbacPathMatcher.Compiled compiled = RbacPathMatcher.compile(pattern);
                if (compiled == null) {
                    log.warn("忽略非法的 api_pattern（** 只允许出现在末尾）: {}", pattern);
                    continue;
                }
                String method = trimToNull(entry.getMethod());
                String normalizedMethod = method == null ? null : method.toUpperCase(Locale.ROOT);
                if (compiled.isLiteral()) {
                    if (normalizedMethod == null) {
                        putIfAbsent(exactAnyMethod, compiled.getNormalizedPattern(), code, pattern, "*");
                    } else {
                        Map<String, String> exact = exactByMethod.get(normalizedMethod);
                        if (exact == null) {
                            exact = new HashMap<String, String>();
                            exactByMethod.put(normalizedMethod, exact);
                        }
                        putIfAbsent(exact, compiled.getNormalizedPattern(), code, pattern, normalizedMethod);
                    }
                } else {
                    Rule rule = new Rule(code, compiled, index);
                    if (normalizedMethod == null) {
                        wildcardAnyMethod.add(rule);
                    } else {
                        List<Rule> rules = wildcardByMethod.get(normalizedMethod);
                        if (rules == null) {
                            rules = new ArrayList<Rule>();
                            wildcardByMethod.put(normalizedMethod, rules);
                        }
                        rules.add(rule);
                    }
                }
            }
        }
        Comparator<Rule> comparator = new Comparator<Rule>() {
            @Override
            public int compare(Rule left, Rule right) {
                int doubleStar = Boolean.compare(left.compiled.isTrailingDoubleStar(),
                        right.compiled.isTrailingDoubleStar());
                if (doubleStar != 0) {
                    return doubleStar;
                }
                int wildcard = Integer.compare(left.compiled.getWildcardSegments(),
                        right.compiled.getWildcardSegments());
                if (wildcard != 0) {
                    return wildcard;
                }
                int segments = Integer.compare(right.compiled.getSegmentCount(),
                        left.compiled.getSegmentCount());
                if (segments != 0) {
                    return segments;
                }
                return Integer.compare(left.index, right.index);
            }
        };
        Collections.sort(wildcardAnyMethod, comparator);
        Map<String, List<Rule>> sortedWildcardByMethod = new HashMap<String, List<Rule>>();
        for (Map.Entry<String, List<Rule>> entry : wildcardByMethod.entrySet()) {
            Collections.sort(entry.getValue(), comparator);
            sortedWildcardByMethod.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        int exactCount = exactAnyMethod.size();
        for (Map<String, String> exact : exactByMethod.values()) {
            exactCount += exact.size();
        }
        int wildcardCount = wildcardAnyMethod.size();
        for (List<Rule> rules : sortedWildcardByMethod.values()) {
            wildcardCount += rules.size();
        }
        Map<String, Map<String, String>> immutableExact = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : exactByMethod.entrySet()) {
            immutableExact.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return new Snapshot(Collections.unmodifiableMap(immutableExact),
                Collections.unmodifiableMap(exactAnyMethod),
                Collections.unmodifiableMap(sortedWildcardByMethod),
                Collections.unmodifiableList(wildcardAnyMethod),
                Collections.unmodifiableList(rawMappings), exactCount, wildcardCount);
    }

    private static void putIfAbsent(Map<String, String> target, String key, String code, String pattern,
            String method) {
        if (target.containsKey(key)) {
            log.warn("映射表中同一 method+pattern 重复配置，保留先出现者: {} {}（忽略 code={}）", method, pattern, code);
            return;
        }
        target.put(key, code);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 不可变映射快照。
     */
    static final class Snapshot {

        private final Map<String, Map<String, String>> exactByMethod;

        private final Map<String, String> exactAnyMethod;

        private final Map<String, List<Rule>> wildcardByMethod;

        private final List<Rule> wildcardAnyMethod;

        private final List<MappingEntry> rawMappings;

        private final int exactCount;

        private final int wildcardCount;

        Snapshot(Map<String, Map<String, String>> exactByMethod, Map<String, String> exactAnyMethod,
                Map<String, List<Rule>> wildcardByMethod, List<Rule> wildcardAnyMethod,
                List<MappingEntry> rawMappings, int exactCount, int wildcardCount) {
            this.exactByMethod = exactByMethod;
            this.exactAnyMethod = exactAnyMethod;
            this.wildcardByMethod = wildcardByMethod;
            this.wildcardAnyMethod = wildcardAnyMethod;
            this.rawMappings = rawMappings;
            this.exactCount = exactCount;
            this.wildcardCount = wildcardCount;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.<String, Map<String, String>>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    Collections.<String, List<Rule>>emptyMap(),
                    Collections.<Rule>emptyList(), Collections.<MappingEntry>emptyList(), 0, 0);
        }

        String match(String httpMethod, String path) {
            String normalizedPath = RbacPathMatcher.normalize(path);
            if (httpMethod != null && !httpMethod.trim().isEmpty()) {
                String method = httpMethod.trim().toUpperCase(Locale.ROOT);
                Map<String, String> exact = exactByMethod.get(method);
                if (exact != null) {
                    String code = exact.get(normalizedPath);
                    if (code != null) {
                        return code;
                    }
                }
                List<Rule> rules = wildcardByMethod.get(method);
                if (rules != null) {
                    for (Rule rule : rules) {
                        if (rule.compiled.matches(normalizedPath)) {
                            return rule.code;
                        }
                    }
                }
            }
            String anyCode = exactAnyMethod.get(normalizedPath);
            if (anyCode != null) {
                return anyCode;
            }
            for (Rule rule : wildcardAnyMethod) {
                if (rule.compiled.matches(normalizedPath)) {
                    return rule.code;
                }
            }
            return null;
        }
    }

    /**
     * 通配规则。
     */
    static final class Rule {

        private final String code;

        private final RbacPathMatcher.Compiled compiled;

        private final int index;

        Rule(String code, RbacPathMatcher.Compiled compiled, int index) {
            this.code = code;
            this.compiled = compiled;
            this.index = index;
        }
    }
}
