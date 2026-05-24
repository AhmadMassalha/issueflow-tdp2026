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
 *
 * <p><b>Slice 10 update:</b> envelope shape now matches
 * {@code .cursor/rules/20-api-contract.mdc} ({@code data/total/page/pageSize},
 * with 1-indexed {@code page} on the wire). Tests assert the 0-indexed
 * Spring → 1-indexed wire conversion.
 */
class PageResponseTest {

    @Test
    @DisplayName("of: maps every item, exposes total, and converts 0-indexed Spring page → 1-indexed wire page")
    void of_mapsAllItems_andConvertsPageIndex() {
        // Spring's PageImpl uses 0-indexed page; we ask for page 1 (the second page).
        var source = new PageImpl<>(
                List.of("a", "b", "c"),
                PageRequest.of(1, 3, Sort.by("x").descending()),
                23L);

        PageResponse<String> response = PageResponse.of(source, String::toUpperCase);

        assertThat(response.data()).containsExactly("A", "B", "C");
        // Wire is 1-indexed: Spring's page=1 → wire page=2.
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(3);
        assertThat(response.total()).isEqualTo(23L);
    }

    @Test
    @DisplayName("of: empty page yields empty data list and zero total; page=1 on the wire (Spring's 0 + 1)")
    void of_emptyPage_yieldsEmptyEnvelope() {
        var source = new PageImpl<Integer>(List.of(), PageRequest.of(0, 20), 0L);

        PageResponse<String> response = PageResponse.of(source, i -> "item-" + i);

        assertThat(response.data()).isEmpty();
        // Spring's 0 → wire 1 (first page).
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.pageSize()).isEqualTo(20);
        assertThat(response.total()).isZero();
    }
}
