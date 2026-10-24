package com.telbank.rbac.common;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON 列（meta/config）规范化：兼容前端传 JSON 对象或 JSON 字符串。
 */
class JsonPayloadsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void normalizesJsonObjectToJsonText() {
        var node = jsonMapper.readTree("{\"dialog\":\"OrderCreateDialog\",\"width\":720}");
        assertThat(JsonPayloads.normalize(node, jsonMapper))
                .isEqualTo("{\"dialog\":\"OrderCreateDialog\",\"width\":720}");
    }

    @Test
    void keepsJsonTextWhenStringNodeContainsValidJson() {
        var node = jsonMapper.readTree("\"{\\\"api\\\":\\\"POST /api/order/export\\\"}\"");
        assertThat(JsonPayloads.normalize(node, jsonMapper))
                .isEqualTo("{\"api\":\"POST /api/order/export\"}");
    }

    @Test
    void rejectsInvalidJsonTextAndHandlesNull() {
        assertThat(JsonPayloads.normalize(null, jsonMapper)).isNull();
        assertThat(JsonPayloads.normalize(jsonMapper.readTree("null"), jsonMapper)).isNull();
        assertThatThrownBy(() -> JsonPayloads.normalize(jsonMapper.readTree("\"not-json\""), jsonMapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPayloads.normalize(jsonMapper.readTree("\"{broken\""), jsonMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void parsesStoredTextBackToNode() {
        assertThat(JsonPayloads.toNode("{\"icon\":\"ShoppingCart\"}", jsonMapper)
                .get("icon").asString()).isEqualTo("ShoppingCart");
        assertThat(JsonPayloads.toNode(null, jsonMapper)).isNull();
        assertThat(JsonPayloads.toNode("", jsonMapper)).isNull();
        assertThat(JsonPayloads.toNode("{broken", jsonMapper)).isNull();
    }
}
