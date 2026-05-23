package com.att.tdp.issueflow.users.api;

import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.users.domain.User;
import java.time.Instant;

/**
 * Response body for every {@code /users/**} endpoint.
 *
 * <p>{@code passwordHash} is intentionally absent — that's the contract per spec
 * 01 §1. The DTO is the source of truth; {@link User#getPasswordHash()} also
 * carries {@code @JsonIgnore} as defense in depth in case the entity is ever
 * serialized directly.
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        Role role,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserResponse from(User u) {
        return new UserResponse(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                u.getFullName(),
                u.getRole(),
                u.getCreatedAt(),
                u.getUpdatedAt()
        );
    }
}
