package com.att.tdp.issueflow.csv;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.csv.api.CsvController;
import com.att.tdp.issueflow.csv.api.CsvImportResponse;
import com.att.tdp.issueflow.csv.api.RowError;
import com.att.tdp.issueflow.csv.service.CsvExportService;
import com.att.tdp.issueflow.csv.service.CsvImportService;
import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * WebMvc-slice coverage for {@link CsvController}: streaming export
 * response, multipart parsing, MIME validation, content-disposition
 * filename, and per-row response shape.
 *
 * <p>{@code addFilters = false} per the slice-3 Gotcha — the JWT chain
 * is out of scope here. Real chain enforcement is covered by
 * {@code CsvIntegrationTest}.
 */
@WebMvcTest(controllers = CsvController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CsvControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CsvExportService exporter;

    @MockitoBean
    private CsvImportService importer;

    // ---- GET /tickets/export -----------------------------------------------

    @Test
    @DisplayName("GET /tickets/export streams CSV bytes with text/csv content-type and dated attachment filename")
    void export_streamsCsv_withCorrectHeaders() throws Exception {
        // Stub the exporter to write a deterministic body.
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("id,title\n1,Hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(exporter).writeCsv(eq(42L), any(OutputStream.class));

        MvcResult result = mvc.perform(get("/tickets/export").param("projectId", "42"))
                .andExpect(status().isOk())
                // StreamingResponseBody dispatches async; await dispatch then assert.
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"tickets-project-42-\\d{8}\\.csv\"")))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string("id,title\n1,Hello\n"));

        verify(exporter).writeCsv(eq(42L), any(OutputStream.class));
    }

    @Test
    @DisplayName("GET /tickets/export with no projectId param → 400 MISSING_PARAMETER")
    void export_missingProjectId_returns400() throws Exception {
        mvc.perform(get("/tickets/export"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    // ---- POST /tickets/import ----------------------------------------------

    @Test
    @DisplayName("POST /tickets/import returns the {created, failed, errors[]} envelope verbatim")
    void import_returnsExpectedEnvelope() throws Exception {
        when(importer.importCsv(eq(7L), any()))
                .thenReturn(new CsvImportResponse(2, 1, List.of(
                        new RowError(3, "Assignee 9 is not a DEVELOPER.", "INVALID_ASSIGNEE"))));

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv",
                "id,title,description,status,priority,type,assigneeId,dueDate\n".getBytes());

        mvc.perform(multipart("/tickets/import").file(file).param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_ASSIGNEE"))
                .andExpect(jsonPath("$.errors[0].message", containsString("DEVELOPER")));
    }

    @Test
    @DisplayName("POST /tickets/import with no projectId form field → 400 MISSING_PARAMETER")
    void import_missingProjectId_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", "id,title\n".getBytes());

        mvc.perform(multipart("/tickets/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @DisplayName("Spec 10 §7: Content-Type application/json on the file part → 415 CSV_UNSUPPORTED_TYPE")
    void import_wrongMime_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "application/json",
                "{\"oops\":true}".getBytes());

        mvc.perform(multipart("/tickets/import").file(file).param("projectId", "7"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("CSV_UNSUPPORTED_TYPE"));
    }

    @Test
    @DisplayName("Spec 10 D7: legacy application/vnd.ms-excel MIME is accepted (Excel/Office often sets this on .csv)")
    void import_excelLegacyMimeAccepted() throws Exception {
        when(importer.importCsv(eq(7L), any()))
                .thenReturn(new CsvImportResponse(0, 0, List.of()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "application/vnd.ms-excel",
                "id,title,description,status,priority,type,assigneeId,dueDate\n".getBytes());

        mvc.perform(multipart("/tickets/import").file(file).param("projectId", "7"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Spec 10 D7: text/csv with charset suffix is accepted")
    void import_textCsvWithCharsetAccepted() throws Exception {
        when(importer.importCsv(eq(7L), any()))
                .thenReturn(new CsvImportResponse(0, 0, List.of()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv; charset=UTF-8",
                "id,title,description,status,priority,type,assigneeId,dueDate\n".getBytes());

        mvc.perform(multipart("/tickets/import").file(file).param("projectId", "7"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Spec 10 D7: null content-type → 415 (defensive; missing header is the same as wrong header)")
    void import_nullContentType_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", null, "id,title\n".getBytes());

        mvc.perform(multipart("/tickets/import").file(file).param("projectId", "7"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("CSV_UNSUPPORTED_TYPE"));
    }
}
