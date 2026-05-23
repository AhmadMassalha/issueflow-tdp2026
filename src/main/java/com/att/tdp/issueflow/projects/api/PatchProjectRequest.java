package com.att.tdp.issueflow.projects.api;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /projects/{projectId}}.
 *
 * <p>Both fields are nullable. Semantics (Session-04 D3):
 * <ul>
 *   <li>{@code null} → leave unchanged.</li>
 *   <li>Non-{@code null} (including empty string) → overwrite.</li>
 *   <li>Both fields {@code null} simultaneously → 400 {@code VALIDATION_FAILED}
 *       with {@code details=[{field:"_body", issue:"…"}]}, enforced by the
 *       service (Session-04 D4).</li>
 * </ul>
 *
 * <p>Fields not declared here (e.g. {@code ownerId}, {@code id}, {@code
 * deletedAt}) are silently ignored by Jackson — spec 03 §4 specifies which
 * fields the endpoint may change, and "ignore unknown" matches the slice-2
 * convention for {@code POST /users/update/{id}}.
 */
public record PatchProjectRequest(
        @Size(max = 128)
        String name,

        @Size(max = 10_000)
        String description
) {}
