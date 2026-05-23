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
}
