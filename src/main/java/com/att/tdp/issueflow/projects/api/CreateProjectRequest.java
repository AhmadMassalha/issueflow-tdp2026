package com.att.tdp.issueflow.projects.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /projects}.
 *
 * <p>{@code ownerId} comes from the body per Session-04 D1 and the README
 * example. The service validates that a user with this id exists and returns
 * {@code USER_NOT_FOUND} (404) if not — spec 03 §1.
 *
 * <p>{@code description} is optional. {@code @Size} bounds it to a sane upper
 * limit so a single request can't blow past the TEXT-column-but-still-in-RAM
 * threshold; 10 000 chars matches the comment-body bound in spec 05.
 */
public record CreateProjectRequest(
        @NotBlank
        @Size(max = 128)
        String name,

        @Size(max = 10_000)
        String description,

        @NotNull
        @Positive
        Long ownerId
) {}
