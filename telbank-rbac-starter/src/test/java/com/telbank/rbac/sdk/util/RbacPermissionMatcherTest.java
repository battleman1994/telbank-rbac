package com.telbank.rbac.sdk.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * 权限码通配匹配（A 方案）：精确 / {@code *} / {@code 前缀:*}。
 */
class RbacPermissionMatcherTest {

    @Test
    void exactMatch() {
        assertThat(RbacPermissionMatcher.matches("order:delete", "order:delete")).isTrue();
        assertThat(RbacPermissionMatcher.matches("order:delete", "order:list")).isFalse();
        assertThat(RbacPermissionMatcher.matches("order:delete", "order:delete:extra")).isFalse();
    }

    @Test
    void wildcardAllMatchesAnyCode() {
        assertThat(RbacPermissionMatcher.matches("*", "order:delete")).isTrue();
        assertThat(RbacPermissionMatcher.matches("*", "purchase:create")).isTrue();
        assertThat(RbacPermissionMatcher.matches("*", "*")).isTrue();
        assertThat(RbacPermissionMatcher.matches("*", "any")).isTrue();
    }

    @Test
    void prefixWildcardMatchesCodesUnderPrefix() {
        assertThat(RbacPermissionMatcher.matches("order:*", "order:delete")).isTrue();
        assertThat(RbacPermissionMatcher.matches("order:*", "order:list")).isTrue();
        // 多级后缀同样命中
        assertThat(RbacPermissionMatcher.matches("order:*", "order:view:amount")).isTrue();
        // 精确前缀本身（无冒号后缀）不算
        assertThat(RbacPermissionMatcher.matches("order:*", "order")).isFalse();
        // 其它前缀不算
        assertThat(RbacPermissionMatcher.matches("order:*", "purchase:create")).isFalse();
        // 前缀通配不匹配全量通配码本身
        assertThat(RbacPermissionMatcher.matches("order:*", "*")).isFalse();
        // 区分大小写
        assertThat(RbacPermissionMatcher.matches("order:*", "Order:delete")).isFalse();
    }

    @Test
    void prefixWildcardOnlyMatchesColonBoundary() {
        // order:* 不能匹配 orderx:delete（前缀必须到冒号边界）
        assertThat(RbacPermissionMatcher.matches("order:*", "orderx:delete")).isFalse();
        assertThat(RbacPermissionMatcher.matches("order:*", "order:")).isTrue();
    }

    @Test
    void nullAndBlankNeverMatch() {
        assertThat(RbacPermissionMatcher.matches(null, "order:delete")).isFalse();
        assertThat(RbacPermissionMatcher.matches("", "order:delete")).isFalse();
        assertThat(RbacPermissionMatcher.matches("order:delete", null)).isFalse();
        assertThat(RbacPermissionMatcher.matches("order:delete", "")).isFalse();
        assertThat(RbacPermissionMatcher.matches("*", null)).isFalse();
        assertThat(RbacPermissionMatcher.matches("*", "   ")).isFalse();
        assertThat(RbacPermissionMatcher.matches("  ", "order:delete")).isFalse();
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list"), "  ")).isFalse();
        assertThat(RbacPermissionMatcher.matchesAny(null, "order:delete")).isFalse();
        assertThat(RbacPermissionMatcher.matchesAny(Collections.<String>emptyList(), "order:delete"))
                .isFalse();
    }

    @Test
    void collectionMatchesAny() {
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list", "purchase:create"), "purchase:create")).isTrue();
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list", "order:*"), "order:delete")).isTrue();
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list", "order:*"), "purchase:create")).isFalse();
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list", "*"), "purchase:create")).isTrue();
        assertThat(RbacPermissionMatcher.matchesAny(
                Arrays.asList("order:list", null), "order:delete")).isFalse();
    }

    @Test
    void hasWildcardAllOnlyForExactStar() {
        assertThat(RbacPermissionMatcher.hasWildcardAll(Arrays.asList("order:list", "*"))).isTrue();
        assertThat(RbacPermissionMatcher.hasWildcardAll(Collections.singletonList("order:*"))).isFalse();
        assertThat(RbacPermissionMatcher.hasWildcardAll(Collections.<String>emptyList())).isFalse();
        assertThat(RbacPermissionMatcher.hasWildcardAll(null)).isFalse();
    }
}
