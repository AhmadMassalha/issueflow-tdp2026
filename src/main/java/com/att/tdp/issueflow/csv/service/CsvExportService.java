package com.att.tdp.issueflow.csv.service;

import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Streams the {@code tickets.csv} export to a caller-supplied
 * {@link OutputStream} — spec 10 §"Acceptance criteria — Export".
 *
 * <p><b>Why the OutputStream signature?</b> Spec 10 §4 calls out
 * {@code StreamingResponseBody}; the controller wraps this method in
 * exactly that, and the Servlet container feeds the response stream in
 * here. We do NOT materialise a {@code List<Ticket>} or even a
 * {@code String} — for a 100k-ticket project that would be tens of MB
 * pinned in the heap during the response.
 *
 * <p><b>Why one big {@code @Transactional} around the stream?</b>
 * {@code Stream<Ticket>} from a Spring Data repository is backed by a
 * JDBC cursor; that cursor stays open as long as the surrounding
 * transaction is open. If the transaction is allowed to close (e.g. on
 * a {@code @Transactional} method around just the {@code .stream()}
 * call), Hibernate closes the cursor and the next {@code .forEach}
 * iteration throws. So the transaction MUST wrap the entire write
 * loop. Read-only since we never mutate. (Session 11 D6.)
 *
 * <p><b>Empty result?</b> Spec §3 — header row + 200. The
 * {@code CSVPrinter} writes the header on construction (because we
 * called {@code .setHeader(...)}); the {@code forEach} just emits zero
 * rows. Same code path, no special-casing.
 *
 * <p><b>Soft-delete filtering:</b> the JPQL query at the repository
 * doesn't need {@code WHERE deleted_at IS NULL} explicitly — Hibernate's
 * {@code @SQLRestriction} on {@link Ticket} appends it for every JPQL
 * select. Same reason {@code findByProjectId} works.
 */
@Service
@RequiredArgsConstructor
public class CsvExportService {

    private final TicketRepository tickets;

    /**
     * Write all non-soft-deleted tickets of {@code projectId} (id-ascending)
     * to {@code out} as RFC-4180 CSV with the spec-§"CSV format" header.
     *
     * <p>The output stream is NOT closed here — the Servlet container owns
     * its lifecycle. We do flush the {@link CSVPrinter} so the last buffered
     * record is committed to the underlying writer before this method
     * returns.
     *
     * @throws IOException if the underlying writer fails (network drop,
     *                     etc.). The container handles this; we propagate.
     */
    @Transactional(readOnly = true)
    public void writeCsv(Long projectId, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setHeader(TicketCsvColumns.HEADER.toArray(new String[0]))
                .build();
        try (CSVPrinter printer = new CSVPrinter(writer, format);
                Stream<Ticket> stream = tickets.streamByProjectIdOrderByIdAsc(projectId)) {
            stream.forEach(t -> {
                try {
                    printer.printRecord((Object[]) TicketCsvColumns.toRow(t));
                } catch (IOException e) {
                    // forEach can't throw checked; bubble as unchecked.
                    // The outer try-with-resources still closes the printer.
                    throw new UncheckedIOException(e);
                }
            });
            printer.flush();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
