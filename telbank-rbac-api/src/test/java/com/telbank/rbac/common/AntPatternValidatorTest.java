package com.telbank.rbac.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * apiPattern 校验口径（契约 §3.4 与 §7.7/§7.8）：{@code {id}} 与 {@code *} 等价单段，{@code **} 仅末尾，
 * 必须以 {@code /} 开头，method 与 apiPattern 同时空或同时非空。
 */
class AntPatternValidatorTest {

    @Test
    void acceptsValidPatterns() {
        assertThat(AntPatternValidator.isValid("/api/order/{id}")).isTrue();
        assertThat(AntPatternValidator.isValid("/api/order/**")).isTrue();
        assertThat(AntPatternValidator.isValid("/api/order/*/items")).isTrue();
        assertThat(AntPatternValidator.isValid("/api/order/export")).isTrue();
        assertThat(AntPatternValidator.isValid("/api/{system}/order/{id}")).isTrue();
    }

    @Test
    void rejectsInvalidPatterns() {
        assertThat(AntPatternValidator.isValid("api/order")).isFalse();
        assertThat(AntPatternValidator.isValid("/api/**/x")).isFalse();
        assertThat(AntPatternValidator.isValid("/api/**x")).isFalse();
        assertThat(AntPatternValidator.isValid("/api/{id")).isFalse();
        assertThat(AntPatternValidator.isValid("/api/id}")).isFalse();
        assertThat(AntPatternValidator.isValid("/api/{}")).isFalse();
        assertThat(AntPatternValidator.isValid("")).isFalse();
        assertThat(AntPatternValidator.isValid(null)).isFalse();
    }

    @Test
    void rejectsInvalidPatternWithReadableMessage() {
        assertThatThrownBy(() -> AntPatternValidator.validatePattern("/api/**/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'**'")
                .hasMessageContaining("last");
        assertThatThrownBy(() -> AntPatternValidator.validatePattern("api/order"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start with '/'");
    }

    @Test
    void validatesMethodAndPatternMustBeBothEmptyOrBothPresent() {
        assertThat(AntPatternValidator.normalizeMethodAndPattern(null, null)).isNull();
        assertThat(AntPatternValidator.normalizeMethodAndPattern("", "  ")).isNull();
        assertThat(AntPatternValidator.normalizeMethodAndPattern("get", "/api/order/**")).isEqualTo("GET");

        assertThatThrownBy(() -> AntPatternValidator.normalizeMethodAndPattern("GET", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> AntPatternValidator.normalizeMethodAndPattern(null, "/api/order/**"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> AntPatternValidator.normalizeMethodAndPattern("G3T", "/api/order/**"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A-Z");
    }
}
