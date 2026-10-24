package com.telbank.rbac.sdk.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 路径模式匹配口径：字面量精确 / 单段通配（* 与 {var} 等价）/ 末尾 **（可零段）/ ** 非末尾非法。
 */
class RbacPathMatcherTest {

    @Test
    void normalizeFoldsSlashesAndDropsTrailingSlashAndQuery() {
        assertThat(RbacPathMatcher.normalize("/api/order/1/")).isEqualTo("/api/order/1");
        assertThat(RbacPathMatcher.normalize("//api///order//1")).isEqualTo("/api/order/1");
        assertThat(RbacPathMatcher.normalize("/api/order?x=1")).isEqualTo("/api/order");
        assertThat(RbacPathMatcher.normalize("")).isEqualTo("/");
        assertThat(RbacPathMatcher.normalize(null)).isEqualTo("/");
    }

    @Test
    void literalPatternMatchesOnlyExactPath() {
        assertThat(RbacPathMatcher.matches("/api/order/export", "/api/order/export")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/export", "/api/order/export/")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/export", "/api/order/exports")).isFalse();
        assertThat(RbacPathMatcher.isLiteral("/api/order/export")).isTrue();
    }

    @Test
    void singleStarMatchesExactlyOneSegment() {
        assertThat(RbacPathMatcher.matches("/api/order/*", "/api/order/123")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/*", "/api/order")).isFalse();
        assertThat(RbacPathMatcher.matches("/api/order/*", "/api/order/123/detail")).isFalse();
    }

    @Test
    void pathVariableIsEquivalentToSingleStar() {
        assertThat(RbacPathMatcher.matches("/api/order/{id}", "/api/order/123")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/{id}", "/api/order/abc-def")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/{id}", "/api/order/123/detail")).isFalse();
        assertThat(RbacPathMatcher.isLiteral("/api/order/{id}")).isFalse();
    }

    @Test
    void trailingDoubleStarMatchesZeroOrMoreSegments() {
        assertThat(RbacPathMatcher.matches("/api/order/**", "/api/order")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/**", "/api/order/")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/**", "/api/order/list")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/**", "/api/order/a/b/c")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/**", "/api/orders")).isFalse();
        assertThat(RbacPathMatcher.matches("/**", "/anything/here")).isTrue();
    }

    @Test
    void doubleStarInMiddleIsInvalid() {
        assertThat(RbacPathMatcher.compile("/api/**/detail")).isNull();
        assertThat(RbacPathMatcher.compile("/api/order/**/detail")).isNull();
        assertThat(RbacPathMatcher.matches("/api/**/detail", "/api/order/detail")).isFalse();
        assertThat(RbacPathMatcher.compile("/api/order/**")).isNotNull();
    }

    @Test
    void partialWildcardsWithinSegmentAreSupported() {
        assertThat(RbacPathMatcher.matches("/api/order/list*", "/api/order/listAll")).isTrue();
        assertThat(RbacPathMatcher.matches("/api/order/list*", "/api/order/export")).isFalse();
    }

    @Test
    void patternIsCaseSensitive() {
        assertThat(RbacPathMatcher.matches("/api/order/list", "/API/order/list")).isFalse();
    }
}
