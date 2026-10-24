package com.telbank.rbac.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaultsToPageOneSizeTwentyWhenMissing() {
        PageQuery pageQuery = PageQuery.of(null, null);
        assertThat(pageQuery.page()).isEqualTo(1);
        assertThat(pageQuery.size()).isEqualTo(20);
        assertThat(pageQuery.offset()).isZero();
    }

    @Test
    void clampsInvalidValuesAndMaxSize() {
        assertThat(PageQuery.of(0, 0)).isEqualTo(new PageQuery(1, 20));
        assertThat(PageQuery.of(-3, -1)).isEqualTo(new PageQuery(1, 20));
        assertThat(PageQuery.of(4, 10_000)).isEqualTo(new PageQuery(4, PageQuery.MAX_SIZE));
        assertThat(PageQuery.of(3, 15).offset()).isEqualTo(30);
    }

    @Test
    void pageResultCarriesPaginationMetadata() {
        PageResult<String> result = PageResult.of(java.util.List.of("a"), 101, new PageQuery(2, 20));
        assertThat(result.items()).containsExactly("a");
        assertThat(result.total()).isEqualTo(101);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
    }
}
