package com.telbank.rbac.common;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通配权限匹配（A 方案）：{@code *} 任意、{@code 前缀:*} 前缀匹配、其余精确匹配；多码 AND 语义。
 */
class PermissionMatcherTest {

    @Test
    void wildcardMatchesAnyPermission() {
        Set<String> granted = Set.of("*");
        assertThat(PermissionMatcher.matches(granted, "role:manage")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "order:delete")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "anything:at:all")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "*")).isTrue();
    }

    @Test
    void prefixWildcardMatchesNamespaceOnly() {
        Set<String> granted = Set.of("order:*");
        assertThat(PermissionMatcher.matches(granted, "order:delete")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "order:list")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "order:view:amount")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "role:manage")).isFalse();
        assertThat(PermissionMatcher.matches(granted, "order")).isFalse();
        assertThat(PermissionMatcher.matches(granted, "orders:delete")).isFalse();
    }

    @Test
    void exactMatchWithoutWildcard() {
        Set<String> granted = Set.of("role:manage", "page:manage");
        assertThat(PermissionMatcher.matches(granted, "role:manage")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "role:manag")).isFalse();
        assertThat(PermissionMatcher.matches(granted, "role:manage:all")).isFalse();
    }

    @Test
    void matchesAllUsesAndSemantics() {
        Set<String> granted = Set.of("*");
        assertThat(PermissionMatcher.matchesAll(granted, "role:manage", "page:manage")).isTrue();

        Set<String> partial = Set.of("role:manage", "order:*");
        assertThat(PermissionMatcher.matchesAll(partial, "role:manage", "order:delete")).isTrue();
        assertThat(PermissionMatcher.matchesAll(partial, "role:manage", "page:manage")).isFalse();
        assertThat(PermissionMatcher.matchesAll(partial)).isFalse();
        assertThat(PermissionMatcher.matchesAll(partial, (String[]) null)).isFalse();
    }

    @Test
    void matchesAnyUsesOrSemantics() {
        Set<String> granted = Set.of("role:manage");
        assertThat(PermissionMatcher.matchesAny(granted, "page:manage", "role:manage")).isTrue();
        assertThat(PermissionMatcher.matchesAny(granted, "page:manage")).isFalse();
        assertThat(PermissionMatcher.matchesAny(granted)).isFalse();
    }

    @Test
    void blankAndNullInputsAreNotMatched() {
        assertThat(PermissionMatcher.matches(null, "role:manage")).isFalse();
        assertThat(PermissionMatcher.matches(Set.of(), "role:manage")).isFalse();
        assertThat(PermissionMatcher.matches(Set.of("*"), null)).isFalse();
        assertThat(PermissionMatcher.matches(Set.of("*"), " ")).isFalse();
    }

    @Test
    void recognizesPrefixWildcardPattern() {
        assertThat(PermissionMatcher.isPrefixWildcard("order:*")).isTrue();
        assertThat(PermissionMatcher.isPrefixWildcard("a:b:*")).isTrue();
        assertThat(PermissionMatcher.isPrefixWildcard("*")).isFalse();
        assertThat(PermissionMatcher.isPrefixWildcard("order:delete")).isFalse();
        assertThat(PermissionMatcher.isPrefixWildcard(":*")).isFalse();
        assertThat(PermissionMatcher.isPrefixWildcard(null)).isFalse();
    }
}
