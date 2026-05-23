package com.att.tdp.issueflow.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.config.JpaConfig;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Slice-level test for {@link ProjectRepository}: confirms the
 * {@code (owner_id, name)} unique constraint is real at the DB level (H2 in
 * PostgreSQL compatibility mode), that {@code @CreatedDate} /
 * {@code @LastModifiedDate} from {@link
 * com.att.tdp.issueflow.common.entity.BaseEntity} populate, and that the two
 * existence-check queries the service relies on behave correctly.
 *
 * <p>{@code @AutoConfigureTestDatabase(replace = NONE)} keeps the H2
 * configuration from {@code src/test/resources/application.yaml} (same pattern
 * as {@code UserRepositoryJpaTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class ProjectRepositoryJpaTest {

    @Autowired
    private ProjectRepository projects;

    @PersistenceContext
    private EntityManager em;

    private Project newProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("test description");
        p.setOwnerId(ownerId);
        return p;
    }

    @BeforeEach
    void wipe() {
        projects.deleteAll();
        em.flush();
    }

    @Test
    @DisplayName("persists a project and populates audit timestamps")
    void should_persistAndPopulateTimestamps() {
        Project saved = projects.save(newProject("alpha", 1L));
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
    }

    // ---- Spec 03 §2 — (owner, name) uniqueness ---------------------------------

    @Test
    @DisplayName("rejects duplicate (owner, name) at the DB level")
    void should_rejectDuplicateOwnerAndName() {
        projects.save(newProject("alpha", 1L));
        em.flush();

        assertThatThrownBy(() -> {
            projects.save(newProject("alpha", 1L));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("allows the same name across different owners (uniqueness is per-owner)")
    void should_allowSameNameDifferentOwner() {
        projects.save(newProject("alpha", 1L));
        em.flush();

        // Different owner, same name — must NOT collide.
        Project second = projects.save(newProject("alpha", 2L));
        em.flush();

        assertThat(second.getId()).isNotNull();
        assertThat(projects.findAll()).hasSize(2);
    }

    // ---- service-support queries ------------------------------------------------

    @Test
    @DisplayName("existsByOwnerIdAndName — true when present, false when not")
    void should_reportExistenceByOwnerAndName() {
        projects.save(newProject("alpha", 1L));
        em.flush();

        assertThat(projects.existsByOwnerIdAndName(1L, "alpha")).isTrue();
        assertThat(projects.existsByOwnerIdAndName(1L, "beta")).isFalse();
        assertThat(projects.existsByOwnerIdAndName(2L, "alpha")).isFalse();
    }

    @Test
    @DisplayName("existsByOwnerIdAndNameAndIdNot — excludes the current row so PATCH rename to same name doesn't false-trigger")
    void should_excludeSelfFromCollisionCheck() {
        Project alpha = projects.save(newProject("alpha", 1L));
        Project beta = projects.save(newProject("beta", 1L));
        em.flush();

        // alpha trying to "rename" to its own name → not a collision.
        assertThat(projects.existsByOwnerIdAndNameAndIdNot(1L, "alpha", alpha.getId())).isFalse();
        // alpha trying to rename to beta's name → IS a collision.
        assertThat(projects.existsByOwnerIdAndNameAndIdNot(1L, "beta", alpha.getId())).isTrue();
        // beta trying to rename to its own name → not a collision.
        assertThat(projects.existsByOwnerIdAndNameAndIdNot(1L, "beta", beta.getId())).isFalse();
    }

    // ---- Slice 9: @SQLDelete + @SQLRestriction + native bypass --------------

    @Test
    @DisplayName("Slice 9: deleteById issues SQLDelete UPDATE; standard queries treat row as missing")
    void should_softDelete_viaSQLDelete() {
        Project saved = projects.saveAndFlush(newProject("doomed", 1L));
        Long id = saved.getId();

        projects.deleteById(id);
        em.flush();

        assertThat(projects.existsByIdIncludingDeleted(id)).isTrue();
        assertThat(projects.findById(id)).isEmpty();
        assertThat(projects.existsById(id)).isFalse();
    }

    @Test
    @DisplayName("Slice 9: @SQLRestriction excludes deleted rows from findAll AND existsByOwnerIdAndName")
    void should_excludeDeleted_fromStandardQueries() {
        Project alive = projects.saveAndFlush(newProject("alive", 1L));
        Project doomed = projects.saveAndFlush(newProject("doomed", 1L));
        projects.deleteById(doomed.getId());
        em.flush();
        em.clear();

        assertThat(projects.findAll()).extracting(Project::getId).containsExactly(alive.getId());
        // Important cross-feature: after a project is soft-deleted, an owner
        // can reuse the freed name on a NEW project — uniqueness derived
        // query is filtered.
        assertThat(projects.existsByOwnerIdAndName(1L, "doomed")).isFalse();
    }

    @Test
    @DisplayName("Slice 9: findAllDeleted surfaces ONLY soft-deleted rows, sorted by id asc")
    void should_findAllDeleted_bypassRestriction() {
        Project alive = projects.saveAndFlush(newProject("alive", 1L));
        Project deletedA = projects.saveAndFlush(newProject("delA", 1L));
        Project deletedB = projects.saveAndFlush(newProject("delB", 2L));
        projects.deleteById(deletedA.getId());
        projects.deleteById(deletedB.getId());
        em.flush();
        em.clear();

        assertThat(projects.findAllDeleted())
                .extracting(Project::getId)
                .containsExactly(deletedA.getId(), deletedB.getId())
                .doesNotContain(alive.getId());
    }

    @Test
    @DisplayName("Slice 9: restoreById affects 1 row, sets deleted_at = NULL (Project has no @Version, so no version bump)")
    void should_restoreById_clearsDeletedAt() {
        Project alive = projects.saveAndFlush(newProject("alive", 1L));
        Long aliveId = alive.getId();
        Project doomed = projects.saveAndFlush(newProject("doomed", 1L));
        Long doomedId = doomed.getId();
        projects.deleteById(doomedId);
        em.flush();
        em.clear();

        assertThat(projects.restoreById(doomedId)).isEqualTo(1);
        // Idempotency guard: re-restoring an already-active row is a no-op
        // because of the WHERE deleted_at IS NOT NULL clause.
        assertThat(projects.restoreById(aliveId)).isZero();
        assertThat(projects.restoreById(99_999L)).isZero();

        em.clear();
        Project restored = projects.findById(doomedId).orElseThrow();
        assertThat(restored.getDeletedAt()).isNull();
    }
}
