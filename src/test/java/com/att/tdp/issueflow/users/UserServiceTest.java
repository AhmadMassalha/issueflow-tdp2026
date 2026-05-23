package com.att.tdp.issueflow.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.users.api.CreateUserRequest;
import com.att.tdp.issueflow.users.api.UpdateUserRequest;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import com.att.tdp.issueflow.users.service.UserService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Branching-logic tests for {@link UserService}.
 *
 * <p>One test per branch per the testing rule. Asserts <i>observable behavior</i>
 * (the returned/persisted user, the thrown exception's {@link ErrorCode}) — not
 * just that mocks were called.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository repo;

    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Slice 7 wiring. Not exercised here (cross-cutting auditing is proven
     * end-to-end in AuditIntegrationTest); mocked away so the constructor
     * gets all the deps it expects.
     */
    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private UserService service;

    private CreateUserRequest createReq;

    @BeforeEach
    void setUp() {
        createReq = new CreateUserRequest(
                "alice", "alice@example.com", "Alice Anderson", Role.DEVELOPER, "s3cret-pw");
    }

    // ---- create --------------------------------------------------------------

    @Test
    @DisplayName("create — hashes password and persists when no duplicates")
    void should_createUser_whenInputIsUnique() {
        when(repo.existsByUsername("alice")).thenReturn(false);
        when(repo.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("s3cret-pw")).thenReturn("$2a$10$hashed");
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = service.create(createReq);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(captor.capture());
        User persisted = captor.getValue();
        assertThat(persisted.getUsername()).isEqualTo("alice");
        assertThat(persisted.getEmail()).isEqualTo("alice@example.com");
        assertThat(persisted.getFullName()).isEqualTo("Alice Anderson");
        assertThat(persisted.getRole()).isEqualTo(Role.DEVELOPER);
        assertThat(persisted.getPasswordHash())
                .isEqualTo("$2a$10$hashed")
                .as("plaintext password must never reach the entity")
                .isNotEqualTo("s3cret-pw");
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    @DisplayName("create — throws USER_DUPLICATE_USERNAME without touching repo.save")
    void should_throw409_whenUsernameTaken() {
        when(repo.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createReq))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_DUPLICATE_USERNAME);

        verify(repo, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("create — throws USER_DUPLICATE_EMAIL when username free but email taken")
    void should_throw409_whenEmailTaken() {
        when(repo.existsByUsername("alice")).thenReturn(false);
        when(repo.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createReq))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_DUPLICATE_EMAIL);

        verify(repo, never()).save(any());
    }

    // ---- findById ------------------------------------------------------------

    @Test
    @DisplayName("findById — returns user when present")
    void should_returnUser_whenFound() {
        User u = new User();
        u.setUsername("alice");
        when(repo.findById(7L)).thenReturn(Optional.of(u));

        assertThat(service.findById(7L)).isSameAs(u);
    }

    @Test
    @DisplayName("findById — throws USER_NOT_FOUND when absent")
    void should_throw404_whenIdMissing() {
        when(repo.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ---- update --------------------------------------------------------------

    @Test
    @DisplayName("update — mutates only fullName and role; username/email/passwordHash untouched")
    void should_updateOnlyAllowedFields() {
        User existing = new User();
        existing.setUsername("alice");
        existing.setEmail("alice@example.com");
        existing.setFullName("Alice Anderson");
        existing.setRole(Role.DEVELOPER);
        existing.setPasswordHash("$2a$10$untouched");
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        User updated = service.update(7L, new UpdateUserRequest("Alice Admin", Role.ADMIN));

        assertThat(updated.getFullName()).isEqualTo("Alice Admin");
        assertThat(updated.getRole()).isEqualTo(Role.ADMIN);
        assertThat(updated.getUsername()).isEqualTo("alice");
        assertThat(updated.getEmail()).isEqualTo("alice@example.com");
        assertThat(updated.getPasswordHash()).isEqualTo("$2a$10$untouched");
        // No explicit save() — JPA dirty-checking handles persistence on commit.
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("update — throws USER_NOT_FOUND when target missing")
    void should_throw404_whenUpdateTargetMissing() {
        when(repo.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.update(7L, new UpdateUserRequest("X", Role.ADMIN)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ---- delete --------------------------------------------------------------

    @Test
    @DisplayName("delete — calls repo.deleteById when target exists")
    void should_deleteWhenPresent() {
        when(repo.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(repo, times(1)).deleteById(7L);
    }

    @Test
    @DisplayName("delete — throws USER_NOT_FOUND without touching deleteById when absent")
    void should_throw404_whenDeleteTargetMissing() {
        when(repo.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(repo, never()).deleteById(any());
    }
}
