package com.att.tdp.issueflow.projects.repository;

import com.att.tdp.issueflow.projects.domain.Project;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link Project}.
 *
 * <p>The two {@code existsBy…} queries support the pre-emptive duplicate check
 * in {@link com.att.tdp.issueflow.projects.service.ProjectService}: the simple
 * one for {@code POST}, the {@code …AndIdNot} one for {@code PATCH} so renaming
 * to your own current name doesn't false-trigger the conflict. Both are
 * derived queries → filtered by {@link Project}'s {@code @SQLRestriction}, so
 * a soft-deleted project's name is free for the same owner to reuse on a new
 * project.
 *
 * <p><b>Native bypass methods (slice 9, spec 08):</b>
 * <ul>
 *   <li>{@link #findAllDeleted()} — {@code GET /projects/deleted} admin
 *       endpoint.</li>
 *   <li>{@link #existsByIdIncludingDeleted(Long)} — 404 vs 409 distinction
 *       for restore.</li>
 *   <li>{@link #restoreById(Long)} — atomic restore + version bump.</li>
 * </ul>
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndNameAndIdNot(Long ownerId, String name, Long id);

    @Query(value = "SELECT * FROM projects WHERE deleted_at IS NOT NULL ORDER BY id ASC",
            nativeQuery = true)
    List<Project> findAllDeleted();

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM projects WHERE id = :id",
            nativeQuery = true)
    boolean existsByIdIncludingDeleted(@Param("id") Long id);

    /**
     * Note: no {@code version} bump here — {@link Project} has no
     * {@code @Version} field (spec 03 doesn't require optimistic locking for
     * projects, unlike Ticket / Comment). If a future slice adds versioning
     * to Project, add {@code version = version + 1} to this UPDATE to match
     * the Ticket pattern.
     */
    @Modifying
    @Query(value = "UPDATE projects SET deleted_at = NULL "
            + "WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);
}
