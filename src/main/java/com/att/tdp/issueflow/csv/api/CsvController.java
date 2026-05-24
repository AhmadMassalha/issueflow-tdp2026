package com.att.tdp.issueflow.csv.api;

import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.csv.service.CsvExportService;
import com.att.tdp.issueflow.csv.service.CsvImportService;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * REST controller for {@code GET /tickets/export} and {@code POST /tickets/import} —
 * spec 10 endpoints.
 *
 * <p><b>Why not on {@code TicketController}?</b> Session 11 D2. The CSV
 * concerns (multipart parsing, streaming, MIME validation) would crowd
 * the existing controller; this module mirrors how {@code dependencies/}
 * orchestrates over {@code tickets/} without polluting it.
 *
 * <p><b>RBAC:</b> {@code @PreAuthorize("isAuthenticated()")} — any
 * authenticated user can export/import (project-membership concerns
 * deferred to slice 13).
 *
 * <p><b>Import is NOT idempotent.</b> Re-uploading the same file creates
 * a fresh set of tickets with new ids. Round-trip id preservation is
 * explicitly NOT supported (Session 11 D10).
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class CsvController {

    /**
     * Per spec §7 — non-{@code text/csv} MIME → 415 {@code CSV_UNSUPPORTED_TYPE}.
     * We also accept {@code application/vnd.ms-excel} because that's what
     * older clients (Office, some Postman versions, some browsers) attach
     * to {@code .csv} files. Session 11 D7 — no byte-sniffing; trust the
     * Content-Type the client sent. Rejecting it would just generate a
     * support ticket.
     */
    private static final Set<String> ACCEPTED_MIME = Set.of(
            "text/csv",
            "application/vnd.ms-excel");

    private static final DateTimeFormatter EXPORT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CsvExportService exporter;
    private final CsvImportService importer;

    // ---- export ------------------------------------------------------------

    /**
     * Spec 10 §1–§4 — stream the project's tickets as a CSV attachment.
     *
     * <p>{@link StreamingResponseBody} runs the body lambda on a Servlet
     * 3+ async thread; the response status + headers commit first, then
     * the body bytes trickle out as the {@code CSVPrinter} writes rows.
     * No data loaded into memory beyond {@code FETCH_SIZE} rows per
     * cursor batch.
     */
    @GetMapping("/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam Long projectId) {
        String filename = "tickets-project-" + projectId + "-"
                + LocalDate.now(ZoneOffset.UTC).format(EXPORT_DATE_FMT) + ".csv";
        StreamingResponseBody body = out -> exporter.writeCsv(projectId, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    // ---- import ------------------------------------------------------------

    /**
     * Spec 10 §5–§10 — multipart CSV upload, per-row creation.
     *
     * <p>Status code reasoning:
     * <ul>
     *   <li>200 OK — request succeeded, even if {@code failed > 0}.
     *       Per-row failures are NOT a request failure; they're
     *       reported in {@code errors[]}.</li>
     *   <li>400 {@code MISSING_PARAMETER} — missing {@code file} or
     *       {@code projectId} (Spring's
     *       {@code MissingServletRequestPartException} /
     *       {@code MissingServletRequestParameterException} hit
     *       {@code GlobalExceptionHandler}).</li>
     *   <li>413 {@code PAYLOAD_TOO_LARGE} — file &gt; 10 MB (Spring's
     *       {@code MaxUploadSizeExceededException} hits the existing
     *       handler — already wired in slice 7).</li>
     *   <li>415 {@code CSV_UNSUPPORTED_TYPE} — wrong MIME.</li>
     *   <li>400 {@code CSV_UNKNOWN_COLUMN} — header doesn't match spec.</li>
     * </ul>
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public CsvImportResponse importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectId") Long projectId) throws IOException {
        rejectIfWrongMime(file);
        return importer.importCsv(projectId, file.getInputStream());
    }

    /**
     * Session 11 D7 — accept canonical + legacy CSV MIME types; reject
     * everything else (including {@code null}, which means the client
     * didn't bother to set one).
     */
    private static void rejectIfWrongMime(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new ValidationException(
                    ErrorCode.CSV_UNSUPPORTED_TYPE,
                    "Uploaded file has no Content-Type. Expected one of: " + ACCEPTED_MIME);
        }
        // commons-csv tolerates a charset suffix like "text/csv; charset=UTF-8".
        String base = contentType.contains(";")
                ? contentType.substring(0, contentType.indexOf(';')).strip().toLowerCase()
                : contentType.strip().toLowerCase();
        if (!ACCEPTED_MIME.contains(base)) {
            throw new ValidationException(
                    ErrorCode.CSV_UNSUPPORTED_TYPE,
                    "Unsupported Content-Type: '" + contentType + "'. Expected one of: " + ACCEPTED_MIME);
        }
    }
}
