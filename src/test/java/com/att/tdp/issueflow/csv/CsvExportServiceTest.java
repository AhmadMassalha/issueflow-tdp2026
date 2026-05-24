package com.att.tdp.issueflow.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.csv.service.CsvExportService;
import com.att.tdp.issueflow.csv.service.TicketCsvColumns;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link CsvExportService}.
 *
 * <p>The repository's {@code Stream<Ticket>} return is stubbed with an
 * in-memory {@link Stream} — fine for behavioural tests of the writer.
 * The "stream must be consumed in a transaction" property is a runtime
 * concern proven by {@code CsvIntegrationTest}; here we only verify the
 * serialisation contract (header row, row order, soft-delete filter
 * delegation, empty-result behaviour, RFC-4180 quoting).
 *
 * <p>{@link Ticket#getId()} is private (inherited from {@code BaseEntity}
 * with no setter), so we set it reflectively via {@link #setId}.
 */
@ExtendWith(MockitoExtension.class)
class CsvExportServiceTest {

    @Mock
    private TicketRepository tickets;

    @InjectMocks
    private CsvExportService exporter;

    // ---- happy path --------------------------------------------------------

    @Test
    @DisplayName("Spec 10 §1: header row + one row per ticket, in id-asc order")
    void writeCsv_emitsHeaderAndDataInOrder() throws IOException {
        Ticket a = ticket(1L, "First", "alpha", TicketStatus.TODO, Priority.LOW,
                TicketType.BUG, null, null);
        Ticket b = ticket(2L, "Second", "beta", TicketStatus.IN_PROGRESS, Priority.HIGH,
                TicketType.FEATURE, 99L, Instant.parse("2026-12-31T00:00:00Z"));
        when(tickets.streamByProjectIdOrderByIdAsc(7L)).thenReturn(Stream.of(a, b));

        String csv = export(7L);
        List<CSVRecord> rows = parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id")).isEqualTo("1");
        assertThat(rows.get(0).get("title")).isEqualTo("First");
        assertThat(rows.get(0).get("description")).isEqualTo("alpha");
        assertThat(rows.get(0).get("status")).isEqualTo("TODO");
        assertThat(rows.get(0).get("priority")).isEqualTo("LOW");
        assertThat(rows.get(0).get("type")).isEqualTo("BUG");
        assertThat(rows.get(0).get("assigneeId")).isEmpty();
        assertThat(rows.get(0).get("dueDate")).isEmpty();
        assertThat(rows.get(1).get("id")).isEqualTo("2");
        assertThat(rows.get(1).get("assigneeId")).isEqualTo("99");
        assertThat(rows.get(1).get("dueDate")).isEqualTo("2026-12-31T00:00:00Z");
    }

    @Test
    @DisplayName("Spec 10 §"
            + "Acceptance criteria — Export"
            + ": header row uses the 8 spec columns in the spec order")
    void writeCsv_headerOrderMatchesSpec() throws IOException {
        when(tickets.streamByProjectIdOrderByIdAsc(1L)).thenReturn(Stream.empty());

        String csv = export(1L);
        String headerLine = csv.split("\r?\n")[0];

        assertThat(headerLine.split(",")).containsExactly(
                "id", "title", "description", "status", "priority", "type", "assigneeId", "dueDate");
        // Sanity: the constant matches what we wrote.
        assertThat(TicketCsvColumns.HEADER).containsExactly(
                "id", "title", "description", "status", "priority", "type", "assigneeId", "dueDate");
    }

    // ---- spec §3 — empty result still returns header --------------------------

    @Test
    @DisplayName("Spec 10 §3: empty result writes ONLY the header row (no data)")
    void writeCsv_emptyResultStillEmitsHeader() throws IOException {
        when(tickets.streamByProjectIdOrderByIdAsc(42L)).thenReturn(Stream.empty());

        String csv = export(42L);
        List<CSVRecord> rows = parse(csv);

        assertThat(rows).isEmpty();
        // But the header line is present:
        assertThat(csv).contains("id,title,description,status,priority,type,assigneeId,dueDate");
    }

    // ---- soft-delete filtering delegated to @SQLRestriction ----------------

    @Test
    @DisplayName("Soft-deleted tickets are filtered upstream by @SQLRestriction; service trusts the stream")
    void writeCsv_trustsRepositoryFilter() throws IOException {
        // The repository's @SQLRestriction("deleted_at IS NULL") guarantees
        // the stream never yields soft-deleted rows. Here we just prove
        // the service emits exactly what the repository emits — no extra
        // filtering, no surprise mutation.
        Ticket only = ticket(5L, "alive", "x", TicketStatus.TODO, Priority.LOW,
                TicketType.BUG, null, null);
        when(tickets.streamByProjectIdOrderByIdAsc(1L)).thenReturn(Stream.of(only));

        List<CSVRecord> rows = parse(export(1L));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("5");
    }

    // ---- RFC-4180 quoting --------------------------------------------------

    @Test
    @DisplayName("RFC-4180 quoting: title with comma, newline, and double-quote round-trips correctly")
    void writeCsv_quotesAwkwardCharacters() throws IOException {
        Ticket weird = ticket(1L,
                "Title, with comma\nand newline\nand \"quotes\"",
                "d", TicketStatus.TODO, Priority.LOW, TicketType.BUG, null, null);
        when(tickets.streamByProjectIdOrderByIdAsc(1L)).thenReturn(Stream.of(weird));

        List<CSVRecord> rows = parse(export(1L));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("Title, with comma\nand newline\nand \"quotes\"");
    }

    // ---- helpers -----------------------------------------------------------

    private String export(Long projectId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.writeCsv(projectId, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static List<CSVRecord> parse(String csv) throws IOException {
        try (CSVParser p = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new java.io.StringReader(csv))) {
            return p.getRecords();
        }
    }

    private static Ticket ticket(Long id, String title, String description,
                                 TicketStatus status, Priority priority, TicketType type,
                                 Long assigneeId, Instant dueDate) {
        Ticket t = new Ticket();
        setId(t, id);
        t.setTitle(title);
        t.setDescription(description);
        t.setStatus(status);
        t.setPriority(priority);
        t.setType(type);
        t.setAssigneeId(assigneeId);
        t.setDueDate(dueDate);
        t.setOverdue(false);
        return t;
    }

    private static void setId(Ticket t, Long id) {
        try {
            // BaseEntity.id is package-private/private with no setter — reflection
            // is the standard pattern for unit-only fixtures that need an id
            // without going through the JPA persistence cycle.
            Field f = findIdField(t.getClass());
            f.setAccessible(true);
            f.set(t, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findIdField(Class<?> c) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id");
    }
}
