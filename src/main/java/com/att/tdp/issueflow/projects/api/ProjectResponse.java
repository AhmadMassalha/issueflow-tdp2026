package com.att.tdp.issueflow.projects.api;

import com.att.tdp.issueflow.projects.domain.Project;

/**
 * Response body for every {@code /projects/**} endpoint.
 *
 * <p>Shape matches the README example exactly: {@code {id, name, description,
 * ownerId}}. Audit timestamps are intentionally omitted from the projection;
 * if a future spec needs them, expand here.
 */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId
) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getOwnerId()
        );
    }
}
