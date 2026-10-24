package com.telbank.rbac.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 列（{@code sys_permission.meta} / {@code sys_page_action.config}）读写工具。
 *
 * <p>数据库用 MySQL JSON 列，Java 实体按 JSON 字符串映射；对外 DTO 中 meta 返回 JSON 对象，
 * config 按契约 §4.2 返回 JSON 字符串。</p>
 */
public final class JsonPayloads {

    private JsonPayloads() {
    }

    /**
     * 把请求中的 JSON 值规范化为可入库的 JSON 字符串。
     *
     * <ul>
     *   <li>null / JSON null → null；</li>
     *   <li>字符串节点：内容必须是合法 JSON 文本，原样存储（兼容前端传 {@code "{}"} 字符串）；</li>
     *   <li>对象/数组/数字/布尔节点：序列化为 JSON 文本。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 非法 JSON 文本
     */
    public static String normalize(JsonNode node, JsonMapper jsonMapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            String text = node.asString();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                jsonMapper.readTree(text);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("config/meta must be a valid JSON text");
            }
            return text;
        }
        return jsonMapper.writeValueAsString(node);
    }

    /**
     * 把库中的 JSON 字符串解析为 JsonNode；空值返回 null。
     */
    public static JsonNode toNode(String stored, JsonMapper jsonMapper) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(stored);
        } catch (RuntimeException ex) {
            // 历史脏数据不应导致接口 500：降级为 null
            return null;
        }
    }
}
