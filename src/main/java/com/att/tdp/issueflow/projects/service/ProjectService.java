package com.att.tdp.issueflow.projects.service;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.api.CreateProjectRequest;
import com.att.tdp.issueflow.projects.api.PatchProjectRequest;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the {@code projects} feature.
 *
 * <p>RBAC: no {@code @PreAuthorize} on this service (per Session-04 D2 / ADR
 * 0006 — all five endpoints are open to any authenticated user). If a future
 * spec adds an ADMIN-only or owner-only rule, this is the place to put it.
 *
 * <p>Owner validation is done by id against {@link UserRepository#existsById}
 * rather than fetching the user — we don't need the user object, only the
 * existence guarantee. Matches the FK-style invariant the spec implies without
 * pulling a JPA association into the entity.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final UserRepository users;
    private final AuditLogService auditLog;

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projects.findAll();
    }

    @Transactional(readOnly = true)
    public Project findById(Long id) {
        return projects.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.PROJECT_NOT_FOUND, "Project " + id + " was not found."));
    }

    public Project create(CreateProjectRequest req) {
        // Spec 03 §1: missing owner → 404 USER_NOT_FOUND (not 422). The wording
        // is explicit, so we surface the user-feature code from this layer.
        if (!users.existsById(req.ownerId())) {
            throw new NotFoundException(
                    ErrorCode.USER_NOT_FOUND,
                    "User " + req.ownerId() + " was not found.");
        }
        // Spec 03 §2: (ownerId, name) is unique per owner.
        if (projects.existsByOwnerIdAndName(req.ownerId(), req.name())) {
            throw new ConflictException(
                    ErrorCode.PROJECT_DUPLICATE_NAME,
                    "Owner " + req.ownerId() + " already has a project named '" + req.name() + "'.");
        }

        Project p = new Project();
        p.setName(req.name());
        p.setDescription(req.description());
        p.setOwnerId(req.ownerId());
        Project saved = projects.save(p);
        auditLog.log(AuditAction.CREATE, EntityType.PROJECT, saved.getId());
        return saved;
    }

    public Project update(Long id, PatchProjectRequest req) {
        // Session-04 D4: both fields absent → 400 with a body-level details
        // entry. Use _body as the field name so the client knows it's not a
        // per-field issue.
        if (req.name() == null && req.description() == null) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED,
                    "At least one of 'name' or 'description' must be provided.",
                    List.of(new ApiError.FieldIssue(
                            "_body",
                            "at least one of name|description must be provided")));
        }

        Project existing = findById(id);

        if (req.name() != null && !req.name().equals(existing.getName())) {
            // Renaming → re-check uniqueness against the same owner. The
            // …AndIdNot variant prevents self-collision on the no-op case
            // ("same owner, same name, same id" must not 409).
            if (projects.existsByOwnerIdAndNameAndIdNot(existing.getOwnerId(), req.name(), id)) {
                throw new ConflictException(
                        ErrorCode.PROJECT_DUPLICATE_NAME,
                        "Owner " + existing.getOwnerId()
                                + " already has a project named '" + req.name() + "'.");
            }
            existing.setName(req.name());
        }
        if (req.description() != null) {
            existing.setDescription(req.description());
        }

        auditLog.log(AuditAction.UPDATE, EntityType.PROJECT, id);
        return existing; // dirty checking persists on commit
    }

    public void delete(Long id) {
        if (!projects.existsById(id)) {
            throw new NotFoundException(
                    ErrorCode.PROJECT_NOT_FOUND, "Project " + id + " was not found.");
        }
        projects.deleteById(id);
        auditLog.log(AuditAction.DELETE, EntityType.PROJECT, id);
    }
}
