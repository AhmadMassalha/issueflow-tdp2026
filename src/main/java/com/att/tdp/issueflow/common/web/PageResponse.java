package com.att.tdp.issueflow.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Standard JSON envelope for every paginated endpoint, per
 * {@code .cursor/rules/20-api-contract.mdc}.
 *
 * <p><b>Slice 10 correction (D6):</b> the original slice-7 version of this
 * record used Spring's native field names {@code (items, page, size,
 * totalItems, totalPages)} with a 0-indexed {@code page}. The always-on
 * api-contract rule mandated {@code (data, total, page, pageSize)} with
 * 1-indexed {@code page} from day one — slice 7 silently diverged.
 * Slice 10 corrects that drift in one place so both paginated endpoints
 * ({@code /audit-logs}, {@code /users/{id}/mentions}) ship the same
 * envelope.
 *
 * <pre>
 * {
 *   "data":     [ … ],   // page contents (mapped DTOs)
 *   "total":    137,     // total matching rows across all pages
 *   "page":     1,       // 1-indexed page number on the wire
 *   "pageSize": 20       // page size (capped server-side at 100)
 * }
 * </pre>
 *
 * <p>The off-by-one between the wire's 1-indexed {@code page} and Spring's
 * 0-indexed {@code Pageable} is contained in {@link #of(Page,
 * java.util.function.Function)} — controllers convert at the boundary
 * ({@code PageRequest.of(page - 1, pageSize)}) and this factory reads back
 * Spring's 0-indexed {@code getNumber()} and adds 1 before returning.
 */
public record PageResponse<T>(
        List<T> data,
        long total,
        int page,
        int pageSize
) {

    /**
     * Convert a Spring Data {@link Page} to the wire envelope. Generic over
     * both source row type {@code S} and response item type {@code T} so the
     * caller can map entity → DTO via a method reference. Adds 1 to Spring's
     * 0-indexed page number to produce the 1-indexed wire value (D8).
     */
    public static <S, T> PageResponse<T> of(Page<S> source, java.util.function.Function<S, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getTotalElements(),
                source.getNumber() + 1, // 0-indexed Spring → 1-indexed wire
                source.getSize()
        );
    }
}
