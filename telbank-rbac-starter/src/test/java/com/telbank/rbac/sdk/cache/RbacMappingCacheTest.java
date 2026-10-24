package com.telbank.rbac.sdk.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.client.MappingEntry;

/**
 * 映射表缓存：精确优先、通配优先级、method 隔离、非法模式跳过。
 */
class RbacMappingCacheTest {

    @Test
    void exactPathBeatsWildcard() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Arrays.asList(
                new MappingEntry("DELETE", "/api/order/export", "order:export-exact"),
                new MappingEntry("DELETE", "/api/order/**", "order:list")));

        assertThat(cache.match("DELETE", "/api/order/export")).isEqualTo("order:export-exact");
        assertThat(cache.match("DELETE", "/api/order/123")).isEqualTo("order:list");
    }

    @Test
    void singleSegmentWildcardBeatsTrailingDoubleStar() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Arrays.asList(
                new MappingEntry("GET", "/api/order/**", "order:list"),
                new MappingEntry("GET", "/api/order/{id}", "order:detail")));

        assertThat(cache.match("GET", "/api/order/123")).isEqualTo("order:detail");
        assertThat(cache.match("GET", "/api/order/123/detail")).isEqualTo("order:list");
    }

    @Test
    void trailingDoubleStarMatchesZeroSegments() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Collections.singletonList(new MappingEntry("GET", "/api/order/**", "order:list")));

        assertThat(cache.match("GET", "/api/order")).isEqualTo("order:list");
        assertThat(cache.match("GET", "/api/order/")).isEqualTo("order:list");
        assertThat(cache.match("GET", "/api/order/list")).isEqualTo("order:list");
    }

    @Test
    void methodMustMatchExactly() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Arrays.asList(
                new MappingEntry("DELETE", "/api/order/{id}", "order:delete"),
                new MappingEntry("GET", "/api/order/**", "order:list")));

        assertThat(cache.match("GET", "/api/order/123")).isEqualTo("order:list");
        assertThat(cache.match("DELETE", "/api/order/123")).isEqualTo("order:delete");
        assertThat(cache.match("POST", "/api/order/123")).isNull();
    }

    @Test
    void invalidPatternsAreSkipped() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Arrays.asList(
                new MappingEntry("GET", "/api/**/detail", "bad:code"),
                new MappingEntry("GET", "/api/order/list", "order:list"),
                new MappingEntry("GET", null, "no:pattern")));

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.match("GET", "/api/order/list")).isEqualTo("order:list");
    }

    @Test
    void replaceIsAtomicAndKeepsLatestSnapshot() {
        RbacMappingCache cache = new RbacMappingCache();
        assertThat(cache.isLoaded()).isFalse();
        cache.replace(Collections.singletonList(new MappingEntry("GET", "/a", "a")));
        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.match("GET", "/a")).isEqualTo("a");

        cache.replace(Collections.singletonList(new MappingEntry("GET", "/b", "b")));
        assertThat(cache.match("GET", "/a")).isNull();
        assertThat(cache.match("GET", "/b")).isEqualTo("b");
        assertThat(cache.getMappings()).hasSize(1);
    }

    @Test
    void methodIsCaseInsensitiveAndPathIsNormalized() {
        RbacMappingCache cache = new RbacMappingCache();
        List<MappingEntry> entries = new ArrayList<MappingEntry>();
        entries.add(new MappingEntry("get", "/api/order/list", "order:list"));
        cache.replace(entries);

        assertThat(cache.match("GET", "//api/order/list/")).isEqualTo("order:list");
    }

    @Test
    void duplicatePatternKeepsFirstOccurrence() {
        RbacMappingCache cache = new RbacMappingCache();
        cache.replace(Arrays.asList(
                new MappingEntry("GET", "/api/order/list", "first"),
                new MappingEntry("GET", "/api/order/list", "second")));

        assertThat(cache.match("GET", "/api/order/list")).isEqualTo("first");
    }
}
