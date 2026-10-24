package com.telbank.rbac.sdk.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.client.PageAction;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * 页面操作元数据缓存：全量替换、按 pageCode 分组、空值处理。
 */
class RbacPageActionCacheTest {

    @Test
    void groupsActionsByPageCodeAndExposesFlatList() {
        RbacPageActionCache cache = new RbacPageActionCache();
        PageAction first = StubRbacOpenApiClient.pageAction("order:list", "order:create");
        PageAction second = StubRbacOpenApiClient.pageAction("order:list", "order:export");
        PageAction third = StubRbacOpenApiClient.pageAction("purchase:list", "purchase:create");
        cache.replace(Arrays.asList(first, second, third));

        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.getAll()).containsExactly(first, second, third);
        assertThat(cache.get("order:list")).containsExactly(first, second);
        assertThat(cache.get("purchase:list")).containsExactly(third);
        assertThat(cache.get("unknown:page")).isEmpty();
        // pageCode 为空返回全部
        assertThat(cache.get(null)).hasSize(3);
    }

    @Test
    void replaceClearsPreviousSnapshot() {
        RbacPageActionCache cache = new RbacPageActionCache();
        cache.replace(Arrays.asList(StubRbacOpenApiClient.pageAction("order:list", "order:create")));
        assertThat(cache.get("order:list")).hasSize(1);

        cache.replace(Arrays.asList(StubRbacOpenApiClient.pageAction("purchase:list", "purchase:create")));
        assertThat(cache.get("order:list")).isEmpty();
        assertThat(cache.get("purchase:list")).hasSize(1);

        cache.replace(null);
        assertThat(cache.getAll()).isEmpty();
        assertThat(cache.isLoaded()).isTrue();
    }
}
