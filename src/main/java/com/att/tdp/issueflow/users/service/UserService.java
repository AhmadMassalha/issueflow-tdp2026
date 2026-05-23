package com.att.tdp.issueflow.users.service;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.users.api.CreateUserRequest;
import com.att.tdp.issueflow.users.api.UpdateUserRequest;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the {@code users} feature.
 *
 * <p>Owns transactions (per {@code .cursor/rules/10-java-style.mdc}) and is the
 * only layer that touches {@link PasswordEncoder} or {@link UserRepository}.
 *
 * <p>Duplicate detection is done pre-insert so the API returns the spec'd
 * feature-specific codes ({@code USER_DUPLICATE_USERNAME} /
 * {@code USER_DUPLICATE_EMAIL}). The DB unique constraints remain as a safety
 * net for the (rare) race between the pre-check and the insert; if the race
 * fires, the global handler turns the resulting
 * {@code DataIntegrityViolationException} into a generic 409 — acceptable
 * because it's only reachable under concurrent identical-input requests.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLog;

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.USER_NOT_FOUND, "User " + id + " was not found."));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public User create(CreateUserRequest req) {
        if (users.existsByUsername(req.username())) {
            throw new ConflictException(
                    ErrorCode.USER_DUPLICATE_USERNAME,
                    "A user with username '" + req.username() + "' already exists.");
        }
        if (users.existsByEmail(req.email())) {
            throw new ConflictException(
                    ErrorCode.USER_DUPLICATE_EMAIL,
                    "A user with email '" + req.email() + "' already exists.");
        }

        User u = new User();
        u.setUsername(req.username());
        u.setEmail(req.email());
        u.setFullName(req.fullName());
        u.setRole(req.role());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        User saved = users.save(u);
        auditLog.log(AuditAction.CREATE, EntityType.USER, saved.getId());
        return saved;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public User update(Long id, UpdateUserRequest req) {
        User u = findById(id);
        u.setFullName(req.fullName());
        u.setRole(req.role());
        // username / email / passwordHash are intentionally NOT mutated here per spec 01 §6.
        auditLog.log(AuditAction.UPDATE, EntityType.USER, id);
        return u; // dirty checking persists on commit
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        if (!users.existsById(id)) {
            throw new NotFoundException(
                    ErrorCode.USER_NOT_FOUND, "User " + id + " was not found.");
        }
        users.deleteById(id);
        auditLog.log(AuditAction.DELETE, EntityType.USER, id);
    }
}
