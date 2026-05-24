package com.att.tdp.issueflow.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.csv.api.CsvImportResponse;
import com.att.tdp.issueflow.csv.service.CsvImportRowExecutor;
import com.att.tdp.issueflow.csv.service.CsvImportService;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mockito unit tests for {@link CsvImportService}: header validation,
 * per-row failure isolation, error bucketing, and bean validation. The
 * "REQUIRES_NEW actually rolls back the failing row" property is a
 * runtime concern proven by {@code CsvIntegrationTest}; here we use a
 * Mockito stub for {@link CsvImportRowExecutor} so we can drive both
 * success and failure paths deterministically.
 */
@ExtendWith(MockitoExtension.class)
class CsvImportServiceTest {

    @Mock
    private CsvImportRowExecutor rowExecutor;

    private CsvImportService importer;
    private Validator validator;

    @BeforeEach
    void wireValidator() {
        // Real validator — same engine as the JSON path uses for @Valid.
        // Mocking it would defeat the test ("yes the constraints I declared
        // actually fire from CSV rows").
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        importer = new CsvImportService(rowExecutor, validator);
    }

    // ---- happy paths -------------------------------------------------------

    @Test
    @DisplayName("All-valid file: every row creates one ticket; errors[] empty")
    void importCsv_allValid_createsAllRows() throws IOException {
        when(rowExecutor.createInIsolation(any())).thenReturn(101L, 102L, 103L);

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T1,d1,TODO,LOW,BUG,,
                ,T2,d2,,MEDIUM,FEATURE,,
                ,T3,d3,IN_PROGRESS,HIGH,TECHNICAL,,2026-12-31T00:00:00Z
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.created()).isEqualTo(3);
        assertThat(response.failed()).isZero();
        assertThat(response.errors()).isEmpty();
        verify(rowExecutor, times(3)).createInIsolation(any());
    }

    @Test
    @DisplayName("Spec 10 D9: inbound id column is read but silently ignored — created row gets server id")
    void importCsv_inboundIdIgnored() throws IOException {
        when(rowExecutor.createInIsolation(any())).thenReturn(101L);

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                42,Ignored-Id,d,TODO,LOW,BUG,,
                """;

        CsvImportResponse response = run(csv);
        assertThat(response.created()).isEqualTo(1);
        // The CreateTicketRequest doesn't carry an id field, so by construction
        // the value 42 cannot influence the create call. Sanity-check via
        // argument captor would be redundant.
    }

    // ---- per-row failure isolation ----------------------------------------

    @Test
    @DisplayName("Spec 10 §8: a single failing row leaves prior + subsequent rows committed")
    void importCsv_perRowIsolation_mixedPassFail() throws IOException {
        when(rowExecutor.createInIsolation(any()))
                .thenReturn(1L) // row 2 ok
                .thenThrow(new NotFoundException(ErrorCode.PROJECT_NOT_FOUND,
                        "Project 99 was not found.")) // row 3 fails
                .thenReturn(2L); // row 4 ok

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,A,a,,LOW,BUG,,
                ,B,b,,MEDIUM,BUG,,
                ,C,c,,HIGH,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.created()).isEqualTo(2);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().get(0).row()).isEqualTo(3); // 1-based, header is row 1
        assertThat(response.errors().get(0).code()).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(response.errors().get(0).message()).contains("Project 99");
    }

    @Test
    @DisplayName("Spec 10 §8: all-fail file returns 0 created, errors[].size == rows")
    void importCsv_allFail() throws IOException {
        when(rowExecutor.createInIsolation(any()))
                .thenThrow(new ValidationException(ErrorCode.INVALID_ASSIGNEE,
                        "Assignee 7 is not a DEVELOPER."));

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,A,a,,LOW,BUG,7,
                ,B,b,,LOW,BUG,7,
                """;

        CsvImportResponse response = run(csv);
        assertThat(response.created()).isZero();
        assertThat(response.failed()).isEqualTo(2);
        assertThat(response.errors())
                .extracting("code")
                .containsExactly("INVALID_ASSIGNEE", "INVALID_ASSIGNEE");
        assertThat(response.errors())
                .extracting("row")
                .containsExactly(2, 3); // header is row 1
    }

    // ---- bean validation ---------------------------------------------------

    @Test
    @DisplayName("Bean validation runs per-row: blank title triggers VALIDATION_FAILED, row stays in errors[]")
    void importCsv_beanValidation_blankTitle() throws IOException {
        // The valid row should still go through.
        when(rowExecutor.createInIsolation(any())).thenReturn(1L);

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,,empty title,,LOW,BUG,,
                ,Ok,fine,,LOW,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().get(0).row()).isEqualTo(2);
        assertThat(response.errors().get(0).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.errors().get(0).message()).contains("title");
        verify(rowExecutor, times(1)).createInIsolation(any());
    }

    @Test
    @DisplayName("Bean validation: missing required priority triggers VALIDATION_FAILED")
    void importCsv_beanValidation_missingPriority() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,,,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.created()).isZero();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.errors().get(0).message()).contains("priority");
        verifyNoInteractions(rowExecutor); // failed validation → never called
    }

    // ---- per-cell parse errors --------------------------------------------

    @Test
    @DisplayName("Bad enum value: unknown status → TICKET_INVALID_STATUS per-row error")
    void importCsv_invalidStatus() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,NOPE,LOW,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("TICKET_INVALID_STATUS");
        verifyNoInteractions(rowExecutor);
    }

    @Test
    @DisplayName("Bad enum value: unknown priority → TICKET_INVALID_PRIORITY per-row error")
    void importCsv_invalidPriority() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,,SOON,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("TICKET_INVALID_PRIORITY");
    }

    @Test
    @DisplayName("Bad enum value: unknown type → TICKET_INVALID_TYPE per-row error")
    void importCsv_invalidType() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,,LOW,EPIC,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("TICKET_INVALID_TYPE");
    }

    @Test
    @DisplayName("Bad ISO-8601 dueDate → VALIDATION_FAILED per-row error")
    void importCsv_invalidDueDate() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,,LOW,BUG,,not-a-date
                """;

        CsvImportResponse response = run(csv);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.errors().get(0).message()).contains("dueDate");
    }

    @Test
    @DisplayName("Non-numeric assigneeId → VALIDATION_FAILED per-row error")
    void importCsv_invalidAssigneeId() throws IOException {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,T,d,,LOW,BUG,abc,
                """;

        CsvImportResponse response = run(csv);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo("VALIDATION_FAILED");
    }

    // ---- header validation -------------------------------------------------

    @Test
    @DisplayName("Spec 10 §"
            + "Unknown columns"
            + ": unknown column → ValidationException(CSV_UNKNOWN_COLUMN) before any row is attempted")
    void importCsv_unknownColumn_throwsBeforeAnyRow() {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate,extra
                ,T,d,,LOW,BUG,,,whatever
                """;

        ValidationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class, () -> run(csv));
        assertThat(ex.code()).isEqualTo(ErrorCode.CSV_UNKNOWN_COLUMN);
        assertThat(ex.getMessage()).contains("extra");
        verifyNoInteractions(rowExecutor); // bail out BEFORE per-row work
    }

    @Test
    @DisplayName("Missing required column also surfaces as CSV_UNKNOWN_COLUMN (one code for both diff directions)")
    void importCsv_missingColumn_throws() {
        // dropped dueDate
        String csv = """
                id,title,description,status,priority,type,assigneeId
                ,T,d,,LOW,BUG,
                """;

        ValidationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class, () -> run(csv));
        assertThat(ex.code()).isEqualTo(ErrorCode.CSV_UNKNOWN_COLUMN);
        assertThat(ex.getMessage()).contains("dueDate");
    }

    @Test
    @DisplayName("Columns in non-spec order are accepted (commons-csv maps by name)")
    void importCsv_columnOrderIrrelevant() throws IOException {
        when(rowExecutor.createInIsolation(any())).thenReturn(1L);

        // Spec uses id,title,description,status,priority,type,assigneeId,dueDate.
        // Here we shuffle them.
        String csv = """
                title,id,priority,description,type,status,dueDate,assigneeId
                T,,LOW,d,BUG,,,
                """;

        CsvImportResponse response = run(csv);
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isZero();
    }

    // ---- catch-all -----------------------------------------------------

    @Test
    @DisplayName("Non-domain runtime exception (e.g. constraint violation) is bucketed as INTERNAL_ERROR per-row, not propagated")
    void importCsv_runtimeException_isCaughtPerRow() throws IOException {
        when(rowExecutor.createInIsolation(any()))
                .thenThrow(new DataIntegrityViolationException("UK_xyz violated"))
                .thenReturn(1L);

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,A,a,,LOW,BUG,,
                ,B,b,,LOW,BUG,,
                """;

        CsvImportResponse response = run(csv);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.errors().get(0).code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(response.errors().get(0).message()).contains("UK_xyz");
    }

    // ---- helper ------------------------------------------------------------

    private CsvImportResponse run(String csv) throws IOException {
        return importer.importCsv(7L,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
