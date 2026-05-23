package com.att.tdp.issueflow.users.domain;

import com.att.tdp.issueflow.common.entity.BaseEntity;
import com.att.tdp.issueflow.common.enums.Role;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Application user.
 *
 * <p>Unique constraints on {@code username} and {@code email} are declared at the
 * table level so the DB enforces them even if a service-level pre-check races.
 * {@link UserService} pre-empts the duplicate path by checking before insert and
 * throwing a feature-specific {@code USER_DUPLICATE_*} {@code ConflictException};
 * the DB constraint is the safety net for the race.
 *
 * <p>{@code passwordHash} is {@code @JsonIgnore} as a defense-in-depth measure —
 * the API contract is enforced by {@code UserResponse} (which does not expose it
 * at all), but a future caller that accidentally serializes this entity directly
 * still won't leak the hash.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 128)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false, columnDefinition = "TEXT")
    private String passwordHash;
}
