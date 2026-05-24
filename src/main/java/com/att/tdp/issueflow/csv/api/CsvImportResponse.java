package com.att.tdp.issueflow.csv.api;

import java.util.List;

/**
 * Response body for {@code POST /tickets/import} (spec 10
 * §"Acceptance criteria — Import"): summary counts + per-row failures.
 *
 * <p>Shape is locked by the spec verbatim: {@code { created, failed, errors: [...] }}.
 * Spec doesn't ask for the created ids, so we don't return them — clients
 * can do {@code GET /tickets?projectId=X} after the import to see what
 * landed if they care.
 *
 * <p><b>Invariant:</b> {@code created + failed == total data rows} (NOT
 * counting the header). {@code errors.size() == failed}. A successful
 * partial import returns 200 with both numbers non-zero — the request
 * itself succeeded, individual rows didn't.
 */
public record CsvImportResponse(
        int created,
        int failed,
        List<RowError> errors
) {}
