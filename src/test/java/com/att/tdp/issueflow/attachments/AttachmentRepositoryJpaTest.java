package com.att.tdp.issueflow.attachments;

import static org.assertj.core.api.Assertions.assertThat;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import com.att.tdp.issueflow.attachments.repository.AttachmentRepository;
import com.att.tdp.issueflow.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Persistence + query coverage for {@link AttachmentRepository}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>Bytes round-trip through {@code @Lob} correctly — what we save
 *       is what we get back (no encoding loss on H2's BLOB / Postgres'
 *       BYTEA).</li>
 *   <li>{@code createdAt} auto-populates via {@code @CreationTimestamp}
 *       (we never set it).</li>
 *   <li>{@link AttachmentRepository#findByTicketIdOrderByIdAsc} returns
 *       all attachments of a given ticket in id-ascending order; other
 *       tickets' attachments are excluded.</li>
 *   <li>{@link AttachmentRepository#deleteByTicketId} removes all
 *       attachments for a ticket and returns the affected count.</li>
 * </ol>
 *
 * <p>{@code @BeforeEach} wipes the table to immunise against cross-class
 * H2 contamination (slice 7 wipe Gotcha + slice 10 namespacing Gotcha
 * combined — both apply when the prior {@code @SpringBootTest}
 * leaves rows behind).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class AttachmentRepositoryJpaTest {

    @Autowired
    private AttachmentRepository attachments;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void clean() {
        attachments.deleteAll();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("@Lob byte[] round-trips: bytes saved are bytes loaded (no encoding loss)")
    void persist_bytesRoundTrip() {
        byte[] payload = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF, 0x42, 0x55, 0x47 };
        Attachment a = newAttachment(1L, "boom.png", "image/png", payload, 99L);
        attachments.save(a);
        em.flush();
        em.clear();

        Attachment loaded = attachments.findById(a.getId()).orElseThrow();
        assertThat(loaded.getData()).containsExactly(payload);
        assertThat(loaded.getSizeBytes()).isEqualTo(payload.length);
        assertThat(loaded.getFilename()).isEqualTo("boom.png");
        assertThat(loaded.getContentType()).isEqualTo("image/png");
        assertThat(loaded.getTicketId()).isEqualTo(1L);
        assertThat(loaded.getUploadedBy()).isEqualTo(99L);
    }

    @Test
    @DisplayName("@CreationTimestamp auto-populates createdAt on save (no explicit set needed)")
    void persist_createdAtAutoPopulated() {
        Attachment a = newAttachment(1L, "x.png", "image/png", new byte[] { 1, 2, 3 }, 99L);
        // Intentionally do NOT set createdAt.
        attachments.save(a);
        em.flush();

        Attachment loaded = attachments.findById(a.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByTicketIdOrderByIdAsc — returns only matching ticket's attachments in id order")
    void findByTicketIdOrderByIdAsc_filtersAndSorts() {
        Attachment t1a = attachments.save(newAttachment(1L, "a.png", "image/png", new byte[] { 1 }, 99L));
        Attachment t1b = attachments.save(newAttachment(1L, "b.png", "image/png", new byte[] { 2 }, 99L));
        attachments.save(newAttachment(2L, "other.png", "image/png", new byte[] { 3 }, 99L));
        em.flush();
        em.clear();

        List<Attachment> got = attachments.findByTicketIdOrderByIdAsc(1L);
        assertThat(got).hasSize(2);
        assertThat(got).extracting(Attachment::getId)
                .containsExactly(t1a.getId(), t1b.getId());
        // Sanity: ticket 2 is excluded.
        assertThat(got).allSatisfy(a -> assertThat(a.getTicketId()).isEqualTo(1L));
    }

    @Test
    @DisplayName("deleteByTicketId — bulk-removes all attachments for the ticket, returns affected count")
    void deleteByTicketId_returnsCount() {
        attachments.save(newAttachment(1L, "a.png", "image/png", new byte[] { 1 }, 99L));
        attachments.save(newAttachment(1L, "b.png", "image/png", new byte[] { 2 }, 99L));
        attachments.save(newAttachment(2L, "other.png", "image/png", new byte[] { 3 }, 99L));
        em.flush();
        em.clear();

        int deleted = attachments.deleteByTicketId(1L);
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(attachments.findByTicketIdOrderByIdAsc(1L)).isEmpty();
        // Ticket 2's attachment survived.
        assertThat(attachments.findByTicketIdOrderByIdAsc(2L)).hasSize(1);
    }

    @Test
    @DisplayName("deleteByTicketId — returns 0 when there are no matching rows")
    void deleteByTicketId_emptyReturnsZero() {
        int deleted = attachments.deleteByTicketId(999L);
        assertThat(deleted).isZero();
    }

    private static Attachment newAttachment(Long ticketId, String filename,
                                            String contentType, byte[] data,
                                            Long uploadedBy) {
        Attachment a = new Attachment();
        a.setTicketId(ticketId);
        a.setFilename(filename);
        a.setContentType(contentType);
        a.setData(data);
        a.setSizeBytes(data.length);
        a.setUploadedBy(uploadedBy);
        return a;
    }
}
