package com.att.tdp.issueflow.csv.api;

/**
 * Per-row failure descriptor inside {@link CsvImportResponse#errors()}
 * (spec 10 §"Acceptance criteria — Import" point 8).
 *
 * <p><b>Row numbering convention:</b> 1-based, counting the HEADER as
 * row 1. So the first DATA row is row 2 (matches what users see when
 * they open the file in a spreadsheet — the spec mandates this exact
 * convention: "{@code errors[]} with the 1-based row index").
 *
 * <p>{@code code} is the {@link com.att.tdp.issueflow.common.web.ErrorCode}
 * name (e.g. {@code "VALIDATION_FAILED"}, {@code "INVALID_ASSIGNEE"},
 * {@code "PROJECT_NOT_FOUND"}) so clients can branch on it the same way
 * they branch on the JSON error envelope's {@code code}. Unknown
 * exceptions degrade to {@code "INTERNAL_ERROR"}.
 */
public record RowError(int row, String message, String code) {}
