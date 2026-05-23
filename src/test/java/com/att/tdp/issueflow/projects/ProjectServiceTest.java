package com.att.tdp.issueflow.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.api.CreateProjectRequest;
import com.att.tdp.issueflow.projects.api.PatchProjectRequest;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.projects.service.ProjectService;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branching-logic tests for {@link ProjectService}.
 *
 * <p>One test per branch. Asserts observable behavior (the returned/persisted
 * project, the thrown exception's {@link ErrorCode}) — not just that mocks
 * were called.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projects;

    @Mock
    private UserRepository users;

    @InjectMocks
    private ProjectService service;

    private static Project existing(Long id, String name, Long ownerId) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setDescription("seed description");
        p.setOwnerId(ownerId);
        return p;
    }

    // ---- create --------------------------------------------------------------

    @Test
    @DisplayName("create — persists and returns when owner exists and name is free")
    void should_create_whenInputValid() {
        var req = new CreateProjectRequest("alpha", "desc", 1L);
        when(users.existsById(1L)).thenReturn(true);
        when(projects.existsByOwnerIdAndName(1L, "alpha")).thenReturn(false);
        when(projects.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project saved = service.create(req);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projects).save(captor.capture());
        Project persisted = captor.getValue();
        assertThat(persisted.getName()).isEqualTo("alpha");
        assertThat(persisted.getDescription()).isEqualTo("desc");
        assertThat(persisted.getOwnerId()).isEqualTo(1L);
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    @DisplayName("create — throws USER_NOT_FOUND (404) when owner does not exist; never touches projects repo")
    void should_throw404_whenOwnerMissing() {
        var req = new CreateProjectRequest("alpha", "desc", 99L);
        when(users.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(projects, never()).save(any());
        verify(projects, never()).existsByOwnerIdAndName(any(), any());
    }

    @Test
    @DisplayName("create — throws PROJECT_DUPLICATE_NAME (409) when (owner, name) already exists")
    void should_throw409_whenDuplicateForOwner() {
        var req = new CreateProjectRequest("alpha", "desc", 1L);
        when(users.existsById(1L)).thenReturn(true);
        when(projects.existsByOwnerIdAndName(1L, "alpha")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_DUPLICATE_NAME);

        verify(projects, never()).save(any());
    }

    // ---- findById ------------------------------------------------------------

    @Test
    @DisplayName("findById — returns project when present")
    void should_findById_whenPresent() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));

        assertThat(service.findById(7L)).isSameAs(p);
    }

    @Test
    @DisplayName("findById — throws PROJECT_NOT_FOUND when absent")
    void should_throw404_whenIdMissing() {
        when(projects.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
    }

    // ---- update --------------------------------------------------------------

    @Test
    @DisplayName("update — name only — does not touch description; no save() (dirty-checking)")
    void should_update_nameOnly() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));
        when(projects.existsByOwnerIdAndNameAndIdNot(1L, "alpha-renamed", 7L)).thenReturn(false);

        Project updated = service.update(7L, new PatchProjectRequest("alpha-renamed", null));

        assertThat(updated.getName()).isEqualTo("alpha-renamed");
        assertThat(updated.getDescription()).isEqualTo("seed description");
        verify(projects, never()).save(any());
    }

    @Test
    @DisplayName("update — description only — does not touch name; no save() (dirty-checking)")
    void should_update_descriptionOnly() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));

        Project updated = service.update(7L, new PatchProjectRequest(null, "new desc"));

        assertThat(updated.getDescription()).isEqualTo("new desc");
        assertThat(updated.getName()).isEqualTo("alpha");
        // No rename → no uniqueness query.
        verify(projects, never()).existsByOwnerIdAndNameAndIdNot(any(), any(), any());
    }

    @Test
    @DisplayName("update — sending the same name as before skips the uniqueness check entirely")
    void should_skipUniquenessCheck_whenNameUnchanged() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));

        service.update(7L, new PatchProjectRequest("alpha", "tweaked desc"));

        verify(projects, never()).existsByOwnerIdAndNameAndIdNot(any(), any(), any());
    }

    @Test
    @DisplayName("update — rename to a name another sibling project already owns → 409 PROJECT_DUPLICATE_NAME")
    void should_throw409_whenRenameCollides() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));
        when(projects.existsByOwnerIdAndNameAndIdNot(1L, "beta", 7L)).thenReturn(true);

        assertThatThrownBy(() ->
                service.update(7L, new PatchProjectRequest("beta", null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_DUPLICATE_NAME);

        assertThat(p.getName()).isEqualTo("alpha"); // not mutated
    }

    @Test
    @DisplayName("update — both fields null → 400 VALIDATION_FAILED with _body detail; never hits the repo")
    void should_throw400_whenBothFieldsAbsent() {
        assertThatThrownBy(() ->
                service.update(7L, new PatchProjectRequest(null, null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(projects, never()).findById(any());
    }

    @Test
    @DisplayName("update — empty-string description clears the field (D3 explicit semantics)")
    void should_clearDescription_whenEmptyStringSent() {
        Project p = existing(7L, "alpha", 1L);
        when(projects.findById(7L)).thenReturn(Optional.of(p));

        Project updated = service.update(7L, new PatchProjectRequest(null, ""));

        assertThat(updated.getDescription()).isEmpty();
    }

    @Test
    @DisplayName("update — throws PROJECT_NOT_FOUND when target missing")
    void should_throw404_whenUpdateTargetMissing() {
        when(projects.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.update(7L, new PatchProjectRequest("new", null)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
    }

    // ---- delete --------------------------------------------------------------

    @Test
    @DisplayName("delete — calls repo.deleteById when target exists")
    void should_deleteWhenPresent() {
        when(projects.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(projects, times(1)).deleteById(7L);
    }

    @Test
    @DisplayName("delete — throws PROJECT_NOT_FOUND without touching deleteById when absent")
    void should_throw404_whenDeleteTargetMissing() {
        when(projects.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);

        verify(projects, never()).deleteById(any());
    }
}
