package com.att.tdp.issueflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Branches of {@link PageResponse#of(org.springframework.data.domain.Page,
 * java.util.function.Function)}: full page, empty page. The mapping function
 * is exercised in both cases so we'd notice if the implementation silently
 * dropped or duplicated items.
 */
class PageResponseTest {

    @Test
    @DisplayName("of: maps every item and copies page/size/totals from the source Page")
    void of_mapsAllItems_andCopiesMetadata() {
        var source = new PageImpl<>(
                List.of("a", "b", "c"),
                PageRequest.of(1, 3, Sort.by("x").descending()),
                23L);

        PageResponse<String> response = PageResponse.of(source, String::toUpperCase);

        assertThat(response.items()).containsExactly("A", "B", "C");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.totalItems()).isEqualTo(23L);
        // ceil(23 / 3) = 8 — proves we're not just echoing the source's value
        // (it's actually computed by PageImpl, but if the entire computation
        // were ever moved into PageResponse this assertion still locks it).
        assertThat(response.totalPages()).isEqualTo(8);
    }

    @Test
    @DisplayName("of: empty page yields empty items list and zero totals")
    void of_emptyPage_yieldsEmptyEnvelope() {
        var source = new PageImpl<Integer>(List.of(), PageRequest.of(0, 20), 0L);

        PageResponse<String> response = PageResponse.of(source, i -> "item-" + i);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalItems()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
