package com.telbank.rbac.sdk.client;

/**
 * 接口映射条目：{@code method + pattern -> code}，来自 {@code sys_permission} 中
 * method 与 api_pattern 均非空且 status=1 的记录。
 *
 * <p>pattern 为路径模式，匹配口径见 {@code com.telbank.rbac.sdk.cache.RbacPathMatcher}：
 * 字面量精确 &gt; 单段通配（{@code *} 或 {@code {var}}，等价）&gt; 末尾 {@code **}。</p>
 */
public class MappingEntry {

    private String method;

    private String pattern;

    private String code;

    public MappingEntry() {
    }

    public MappingEntry(String method, String pattern, String code) {
        this.method = method;
        this.pattern = pattern;
        this.code = code;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return "MappingEntry{" + method + ' ' + pattern + " -> " + code + '}';
    }
}
