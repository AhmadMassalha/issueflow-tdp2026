package com.att.tdp.issueflow.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Standard JSON envelope for paginated endpoints (Session 07 D4).
 *
 * <p>Defined here instead of returning Spring's {@code Page<T>} directly
 * because the default {@code PageImpl} JSON serialization carries 10+
 * fields ({@code pageable}, {@code sort}, {@code last}, {@code first},
 * {@code numberOfElements}, …) that clients shouldn't be coupled to. This
 * envelope exposes the five that matter:
 *
 * <pre>
 * {
 *   "items":      [ … ],   // the page contents
 *   "page":       0,       // 0-indexed page number
 *   "size":       20,      // page size (capped server-side)
 *   "totalItems": 137,     // total matching rows
 *   "totalPages": 7        // ceil(totalItems / size)
 * }
 * </pre>
 *
 * <p>Used by spec 06 (audit log) and earmarked for slice 10 (mentions
 * inbox). Any future paginated endpoint should reuse this envelope rather
 * than inventing a new shape.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {

    /**
     * Convert a Spring Data {@link Page} to the envelope. Generic over both
     * the source row type {@code S} and the response item type {@code T}
     * so the caller can map entity → DTO in one place (typically a static
     * {@code Response::from} method reference).
     */
    public static <S, T> PageResponse<T> of(Page<S> source, java.util.function.Function<S, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
