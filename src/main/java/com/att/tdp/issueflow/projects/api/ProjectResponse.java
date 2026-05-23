package com.att.tdp.issueflow.projects.api;

import com.att.tdp.issueflow.projects.domain.Project;
import java.time.Instant;

/**
 * Response body for every {@code /projects/**} endpoint.
 *
 * <p>Shape matches the README example exactly: {@code {id, name, description,
 * ownerId}}. Audit timestamps are intentionally omitted from the projection;
 * if a future spec needs them, expand here.
 *
 * <p><b>Slice 9 — {@code deletedAt}:</b> nullable. {@code null} for every
 * active project; populated only by the admin-only
 * {@code GET /projects/deleted} surface. Session 09 D6.
 */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        Instant deletedAt
) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getOwnerId(),
                p.getDeletedAt()
        );
    }
}
