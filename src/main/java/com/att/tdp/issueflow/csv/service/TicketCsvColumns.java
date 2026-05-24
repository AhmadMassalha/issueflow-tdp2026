package com.att.tdp.issueflow.csv.service;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVRecord;

/**
 * Single source of truth for the {@code tickets.csv} schema.
 *
 * <p>Spec 10 §"CSV format" fixes 8 columns in this order:
 * {@code id, title, description, status, priority, type, assigneeId, dueDate}.
 * Both writers ({@link CsvExportService}) and readers
 * ({@link CsvImportService}) MUST bind to {@link #HEADER} — inlining the
 * column names twice is the classic divergence trap, so this class exists
 * exclusively to prevent it. (Session 11 D1.)
 *
 * <p><b>On the {@code id} column at import time (Session 11 D9):</b> the
 * spec includes {@code id} in the header for human-readable exports, but
 * import always creates a fresh row with a server-assigned id. The inbound
 * {@code id} value is read and silently ignored — erroring on it would
 * mean exported files can't be re-imported, which is a footgun. Round-trip
 * id preservation (UPSERT semantics) is explicitly NOT supported (Session
 * 11 D10).
 *
 * <p><b>Encoding:</b> empty fields are written as the empty string;
 * {@code commons-csv}'s default reader returns the empty string for blank
 * cells. Both directions normalise {@code ""} ↔ {@code null} at the
 * boundary so the rest of the codebase doesn't have to think about it.
 *
 * <p><b>Dates:</b> ISO-8601 ({@code Instant.toString()} on the wire, both
 * directions). Matches the JSON convention used everywhere else in the
 * codebase.
 */
public final class TicketCsvColumns {

    public static final String ID = "id";
    public static final String TITLE = "title";
    public static final String DESCRIPTION = "description";
    public static final String STATUS = "status";
    public static final String PRIORITY = "priority";
    public static final String TYPE = "type";
    public static final String ASSIGNEE_ID = "assigneeId";
    public static final String DUE_DATE = "dueDate";

    /** Authoritative column order. Used both as the export header and as the import allow-list. */
    public static final List<String> HEADER = List.of(
            ID, TITLE, DESCRIPTION, STATUS, PRIORITY, TYPE, ASSIGNEE_ID, DUE_DATE);

    private TicketCsvColumns() {}

    // ---- export: Ticket → String[] -----------------------------------------

    /**
     * Project a {@link Ticket} into the row representation that
     * {@code CSVPrinter.printRecord(Object[])} can serialise. The returned
     * array is positionally aligned with {@link #HEADER}.
     */
    public static String[] toRow(Ticket t) {
        return new String[] {
                t.getId() == null ? "" : String.valueOf(t.getId()),
                nullToEmpty(t.getTitle()),
                nullToEmpty(t.getDescription()),
                t.getStatus() == null ? "" : t.getStatus().name(),
                t.getPriority() == null ? "" : t.getPriority().name(),
                t.getType() == null ? "" : t.getType().name(),
                t.getAssigneeId() == null ? "" : String.valueOf(t.getAssigneeId()),
                t.getDueDate() == null ? "" : t.getDueDate().toString(),
        };
    }

    // ---- import: header check ----------------------------------------------

    /**
     * Spec 10 §"Unknown columns" — strict allow-list. Extra columns and/or
     * missing columns both surface as 400 {@code CSV_UNKNOWN_COLUMN} with
     * the offending names in {@code details[]}. Order is irrelevant
     * (commons-csv binds by name once the header is parsed).
     *
     * <p>Session 11 D8: one error code for both directions of the diff —
     * "your header doesn't match the contract" is one conceptual error,
     * and the {@code details[]} array is where the parser tells the
     * client which columns went wrong.
     */
    public static void validateHeader(Set<String> actualColumns) {
        Set<String> expected = new LinkedHashSet<>(HEADER);
        List<String> unknown = new ArrayList<>();
        for (String c : actualColumns) {
            if (!expected.contains(c)) {
                unknown.add(c);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String c : expected) {
            if (!actualColumns.contains(c)) {
                missing.add(c);
            }
        }
        if (unknown.isEmpty() && missing.isEmpty()) {
            return;
        }
        List<ApiError.FieldIssue> details = new ArrayList<>();
        for (String u : unknown) {
            details.add(new ApiError.FieldIssue(u, "unknown column"));
        }
        for (String m : missing) {
            details.add(new ApiError.FieldIssue(m, "required column missing"));
        }
        throw new ValidationException(
                ErrorCode.CSV_UNKNOWN_COLUMN,
                "CSV header does not match the contract: "
                        + (unknown.isEmpty() ? "" : "unknown=" + unknown + " ")
                        + (missing.isEmpty() ? "" : "missing=" + missing),
                details);
    }

    // ---- import: CSVRecord → CreateTicketRequest ---------------------------

    /**
     * Convert one parsed CSV row into a {@link CreateTicketRequest} for
     * {@code TicketService.create(...)}. The {@code id} column (if present)
     * is ignored (D9). {@code projectId} is supplied by the request form
     * field, NOT by the CSV — spec 10 §5 makes it a separate parameter.
     *
     * <p>Conversion errors surface as {@link ValidationException} with the
     * feature-specific {@code TICKET_INVALID_*} codes that the existing
     * deserialisers use for the JSON path, so a row that breaks parsing
     * comes back through the spec-mandated channels.
     *
     * <p>Bean-validation rules (e.g. {@code @NotBlank title}) are NOT
     * checked here — that's the caller's job via {@code jakarta.validation.Validator}.
     * Splitting "parse" from "validate" keeps each layer doing one thing.
     */
    public static CreateTicketRequest parseRow(CSVRecord record, Long projectId) {
        String title = blankToNull(getOrEmpty(record, TITLE));
        String description = blankToNull(getOrEmpty(record, DESCRIPTION));
        String statusRaw = blankToNull(getOrEmpty(record, STATUS));
        String priorityRaw = blankToNull(getOrEmpty(record, PRIORITY));
        String typeRaw = blankToNull(getOrEmpty(record, TYPE));
        String assigneeIdRaw = blankToNull(getOrEmpty(record, ASSIGNEE_ID));
        String dueDateRaw = blankToNull(getOrEmpty(record, DUE_DATE));

        TicketStatus status = statusRaw == null ? null
                : parseEnum(statusRaw, TicketStatus.class,
                        ErrorCode.TICKET_INVALID_STATUS, STATUS);
        Priority priority = priorityRaw == null ? null
                : parseEnum(priorityRaw, Priority.class,
                        ErrorCode.TICKET_INVALID_PRIORITY, PRIORITY);
        TicketType type = typeRaw == null ? null
                : parseEnum(typeRaw, TicketType.class,
                        ErrorCode.TICKET_INVALID_TYPE, TYPE);
        Long assigneeId = assigneeIdRaw == null ? null
                : parsePositiveLong(assigneeIdRaw, ASSIGNEE_ID);
        Instant dueDate = dueDateRaw == null ? null
                : parseInstant(dueDateRaw, DUE_DATE);

        return new CreateTicketRequest(
                title, description, status, priority, type, projectId, assigneeId, dueDate);
    }

    // ---- helpers -----------------------------------------------------------

    private static String getOrEmpty(CSVRecord r, String column) {
        return r.isMapped(column) ? r.get(column) : "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static <E extends Enum<E>> E parseEnum(
            String raw, Class<E> enumClass, ErrorCode code, String field) {
        try {
            return Enum.valueOf(enumClass, raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    code,
                    field + "='" + raw + "' is not a valid " + enumClass.getSimpleName() + ".",
                    List.of(new ApiError.FieldIssue(field, "must be one of " + listEnumNames(enumClass))));
        }
    }

    private static <E extends Enum<E>> String listEnumNames(Class<E> enumClass) {
        E[] vals = enumClass.getEnumConstants();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vals[i].name());
        }
        return sb.append("]").toString();
    }

    private static Long parsePositiveLong(String raw, String field) {
        try {
            long v = Long.parseLong(raw);
            if (v <= 0) {
                throw new ValidationException(
                        ErrorCode.VALIDATION_FAILED,
                        field + "='" + raw + "' must be a positive number.",
                        List.of(new ApiError.FieldIssue(field, "must be positive")));
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED,
                    field + "='" + raw + "' is not a valid number.",
                    List.of(new ApiError.FieldIssue(field, "must be a number")));
        }
    }

    private static Instant parseInstant(String raw, String field) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED,
                    field + "='" + raw + "' is not a valid ISO-8601 instant.",
                    List.of(new ApiError.FieldIssue(field, "must be ISO-8601 (e.g. 2026-12-31T00:00:00Z)")));
        }
    }

    // Visible for tests / sanity checks if needed.
    static Set<String> expectedColumns() {
        return new HashSet<>(HEADER);
    }
}
