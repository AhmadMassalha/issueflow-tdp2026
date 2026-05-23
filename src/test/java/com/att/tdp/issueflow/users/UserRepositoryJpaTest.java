package com.att.tdp.issueflow.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.config.JpaConfig;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
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
 * Slice-level test for {@link UserRepository}: confirms the unique constraints
 * on {@code username} and {@code email} are real at the DB level (H2 in
 * PostgreSQL compatibility mode), and that {@code @CreatedDate}/{@code @LastModifiedDate}
 * from {@link com.att.tdp.issueflow.common.entity.BaseEntity} populate.
 *
 * <p>Uses {@code @AutoConfigureTestDatabase(replace = NONE)} so we get the H2
 * datasource defined in {@code src/test/resources/application.yaml} rather than
 * Spring Boot's embedded-overrides default.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class UserRepositoryJpaTest {

    @Autowired
    private UserRepository users;

    @PersistenceContext
    private EntityManager em;

    private User newUser(String username, String email) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName("Test User");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash("$2a$10$dummy");
        return u;
    }

    @BeforeEach
    void wipe() {
        users.deleteAll();
        em.flush();
    }

    @Test
    @DisplayName("persists a user and populates audit timestamps")
    void should_persistAndPopulateTimestamps() {
        User saved = users.save(newUser("alice", "alice@example.com"));
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("rejects duplicate username at the DB level")
    void should_rejectDuplicateUsername() {
        users.save(newUser("alice", "alice@example.com"));
        em.flush();

        assertThatThrownBy(() -> {
            users.save(newUser("alice", "alice2@example.com"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("rejects duplicate email at the DB level")
    void should_rejectDuplicateEmail() {
        users.save(newUser("alice", "alice@example.com"));
        em.flush();

        assertThatThrownBy(() -> {
            users.save(newUser("alice2", "alice@example.com"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByUsername returns the saved user; empty when absent")
    void should_findByUsername() {
        users.save(newUser("alice", "alice@example.com"));
        em.flush();

        assertThat(users.findByUsername("alice")).isPresent();
        assertThat(users.findByUsername("nobody")).isEmpty();
    }

    @Test
    @DisplayName("existsByUsername / existsByEmail report correctly")
    void should_reportExistence() {
        users.save(newUser("alice", "alice@example.com"));
        em.flush();

        assertThat(users.existsByUsername("alice")).isTrue();
        assertThat(users.existsByUsername("nobody")).isFalse();
        assertThat(users.existsByEmail("alice@example.com")).isTrue();
        assertThat(users.existsByEmail("nobody@example.com")).isFalse();
    }
}
