package com.att.tdp.issueflow.csv.service;

import com.att.tdp.issueflow.common.exception.DomainException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.csv.api.CsvImportResponse;
import com.att.tdp.issueflow.csv.api.RowError;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

/**
 * Spec 10 §"Acceptance criteria — Import" — parse the multipart CSV,
 * validate the header (spec §"Unknown columns"), and create tickets
 * row-by-row with per-row failure isolation (§8).
 *
 * <p><b>Transaction shape (Session 11 D4):</b> this class is NOT
 * {@code @Transactional}. Each row goes through
 * {@link CsvImportRowExecutor#createInIsolation} which IS
 * {@code @Transactional(REQUIRES_NEW)} — so a failing row rolls back
 * only its own transaction, prior successful rows survive. Without
 * that split, the first failing row would mark a shared outer
 * transaction rollback-only and we'd lose every successful row before
 * it.
 *
 * <p><b>Whole-file rollback only on parser failure:</b> spec §8. A
 * malformed CSV (commons-csv throws {@code IOException}) takes the
 * caller's request status to 400 via the exception handler; nothing
 * was committed because we hadn't started any row transactions yet.
 *
 * <p><b>Header validation order:</b> we deliberately read the header
 * (and check it) BEFORE the first row is consumed — that way a totally
 * unrelated file (e.g. someone uploaded a JSON) fails fast with
 * {@code CSV_UNKNOWN_COLUMN} rather than emitting a meaningless
 * per-row error trail.
 *
 * <p><b>Programmatic Bean Validation:</b> JSON requests go through
 * Spring MVC's {@code @Valid} which is wired to a {@code Validator}
 * automatically. The CSV path bypasses MVC, so we run the same
 * {@link Validator} here by hand to catch {@code @NotBlank},
 * {@code @Size}, {@code @Positive} etc. — the same constraints
 * declared on {@link CreateTicketRequest}. Failures become per-row
 * errors with code {@code VALIDATION_FAILED} (mirroring the JSON
 * path's behaviour).
 */
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final CsvImportRowExecutor rowExecutor;
    private final Validator validator;

    /**
     * Drive the per-row pipeline. Returns a populated
     * {@link CsvImportResponse} regardless of how many rows failed
     * individually — the request as a whole only fails when the CSV
     * itself is malformed (parser exception) or the header is wrong
     * (spec §"Unknown columns" → 400 {@code CSV_UNKNOWN_COLUMN}).
     *
     * @param projectId the project all rows attach to (spec §5 — form field)
     * @param stream    the uploaded CSV bytes (UTF-8)
     */
    public CsvImportResponse importCsv(Long projectId, InputStream stream) throws IOException {
        // commons-csv's RFC4180 parser handles quoted-newlines correctly.
        // setHeader() with NO args + setSkipHeaderRecord(true) makes it
        // auto-detect the header from the first record and map subsequent
        // records by name (so column order is irrelevant per D8).
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        int created = 0;
        int failed = 0;
        List<RowError> errors = new ArrayList<>();

        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {

            // Spec §"Unknown columns" — strict allow-list. Throws
            // ValidationException(CSV_UNKNOWN_COLUMN) which propagates
            // to the controller and becomes a 400 — NO rows have been
            // attempted at this point.
            TicketCsvColumns.validateHeader(parser.getHeaderMap().keySet());

            for (CSVRecord record : parser) {
                // record.getRecordNumber() is 1-based and counts the header,
                // so the first DATA row is row 2. Matches spec §8 "1-based
                // row index" + how spreadsheet apps number rows. See
                // RowError javadoc.
                int rowNumber = (int) record.getRecordNumber() + 1;

                try {
                    CreateTicketRequest req = TicketCsvColumns.parseRow(record, projectId);
                    runBeanValidation(req);
                    rowExecutor.createInIsolation(req);
                    created++;
                } catch (DomainException ex) {
                    errors.add(new RowError(rowNumber, ex.getMessage(), ex.code().name()));
                    failed++;
                } catch (RuntimeException ex) {
                    // Catch-all: e.g. DataIntegrityViolationException if a
                    // unique constraint goes off in some edge case the
                    // service layer didn't pre-empt. Degrade gracefully —
                    // we don't want one malformed row to kill the import.
                    errors.add(new RowError(rowNumber,
                            ex.getMessage() == null ? "Unexpected failure" : ex.getMessage(),
                            ErrorCode.INTERNAL_ERROR.name()));
                    failed++;
                }
            }
        }
        // Whole-file CSV parse errors leak as IOException → caller's
        // RestControllerAdvice maps to a generic 400 / 500 envelope.
        // Spec §8 explicitly says "Whole-file rollback only on parser
        // failure" — we never started any tx if we crash here.

        return new CsvImportResponse(created, failed, errors);
    }

    /**
     * Run the same bean-validation constraints the JSON path runs via
     * {@code @Valid}. Failures become a {@link ValidationException}
     * with the {@code VALIDATION_FAILED} code and per-field details,
     * which the importer loop converts into a {@link RowError}.
     */
    private void runBeanValidation(CreateTicketRequest req) {
        Set<ConstraintViolation<CreateTicketRequest>> violations = validator.validate(req);
        if (violations.isEmpty()) {
            return;
        }
        List<ApiError.FieldIssue> details = violations.stream()
                .map(v -> new ApiError.FieldIssue(v.getPropertyPath().toString(), v.getMessage()))
                .collect(Collectors.toList());
        String summary = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        throw new ValidationException(
                ErrorCode.VALIDATION_FAILED,
                "Row validation failed: " + summary,
                details);
    }
}
