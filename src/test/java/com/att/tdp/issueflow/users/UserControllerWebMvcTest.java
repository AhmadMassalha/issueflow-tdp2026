package com.att.tdp.issueflow.users;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.users.api.CreateUserRequest;
import com.att.tdp.issueflow.users.api.UpdateUserRequest;
import com.att.tdp.issueflow.users.api.UserController;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer coverage for {@code /users/**}.
 *
 * <p>Each test maps to at least one acceptance criterion in
 * {@code docs/spec/01-users.md} (referenced as "Spec 01 §N" in the test JavaDoc).
 *
 * <p>The {@code @Import} list mirrors the slice-1 lesson logged in
 * {@code .cursor/rules/30-testing.mdc}: {@link GlobalExceptionHandler} must be
 * imported explicitly so the error envelope mapping is exercised here.
 *
 * <p>Filters are disabled ({@code addFilters = false}) so that Spring Security's
 * default "everything is 401" auto-config does not interfere with controller-
 * logic assertions. End-to-end security behavior (authn required, ADMIN-only on
 * mutating endpoints) lives in {@code SecurityIntegrationTest}.
 */
@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private UserService service;

    private static User userFixture(long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        u.setFullName("Alice Anderson");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash("$2a$10$NEVER_SHOWN");
        u.setCreatedAt(Instant.parse("2026-05-23T10:00:00Z"));
        u.setUpdatedAt(Instant.parse("2026-05-23T10:00:00Z"));
        return u;
    }

    // ---- Spec 01 §1 — create user, never echoes password ----------------------

    @Test
    @DisplayName("POST /users — 200 on success, response contains no password/passwordHash field")
    void should_create_andOmitPasswordFromResponse() throws Exception {
        User saved = userFixture(1L);
        when(service.create(any(CreateUserRequest.class))).thenReturn(saved);

        var body = json.writeValueAsString(new CreateUserRequest(
                "alice", "alice@example.com", "Alice Anderson", Role.DEVELOPER, "s3cret-pw"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.fullName").value("Alice Anderson"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ---- Spec 01 §2 — duplicate username -------------------------------------

    @Test
    @DisplayName("POST /users — 409 USER_DUPLICATE_USERNAME when username taken")
    void should_return409_whenUsernameDuplicate() throws Exception {
        when(service.create(any(CreateUserRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.USER_DUPLICATE_USERNAME,
                        "A user with username 'alice' already exists."));

        var body = json.writeValueAsString(new CreateUserRequest(
                "alice", "alice@example.com", "Alice Anderson", Role.DEVELOPER, "s3cret-pw"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.code").value("USER_DUPLICATE_USERNAME"))
                .andExpect(jsonPath("$.path").value("/users"));
    }

    // ---- Spec 01 §2 — duplicate email ----------------------------------------

    @Test
    @DisplayName("POST /users — 409 USER_DUPLICATE_EMAIL when email taken")
    void should_return409_whenEmailDuplicate() throws Exception {
        when(service.create(any(CreateUserRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.USER_DUPLICATE_EMAIL,
                        "A user with email 'alice@example.com' already exists."));

        var body = json.writeValueAsString(new CreateUserRequest(
                "alice", "alice@example.com", "Alice Anderson", Role.DEVELOPER, "s3cret-pw"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_DUPLICATE_EMAIL"));
    }

    // ---- Spec 01 §3 — unknown role -------------------------------------------

    @Test
    @DisplayName("POST /users — 400 USER_INVALID_ROLE on unknown role string (handled by RoleJsonDeserializer)")
    void should_return400_whenRoleIsUnknown() throws Exception {
        String body = """
                {
                  "username": "alice",
                  "email": "alice@example.com",
                  "fullName": "Alice Anderson",
                  "role": "WIZARD",
                  "password": "s3cret-pw"
                }
                """;

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.code").value("USER_INVALID_ROLE"))
                .andExpect(jsonPath("$.details[?(@.field == 'role')]", hasSize(1)));
    }

    // ---- Spec 01 §4 — invalid email / username pattern -----------------------

    @Test
    @DisplayName("POST /users — 400 VALIDATION_FAILED with details[] when username pattern invalid")
    void should_return400_whenUsernamePatternInvalid() throws Exception {
        var body = json.writeValueAsString(new CreateUserRequest(
                "x!", "alice@example.com", "Alice Anderson", Role.DEVELOPER, "s3cret-pw"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("username")));
    }

    @Test
    @DisplayName("POST /users — 400 VALIDATION_FAILED with details[] when email malformed")
    void should_return400_whenEmailMalformed() throws Exception {
        var body = json.writeValueAsString(new CreateUserRequest(
                "alice", "not-an-email", "Alice Anderson", Role.DEVELOPER, "s3cret-pw"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("email")));
    }

    // ---- Spec 01 §5 — list + fetch by id -------------------------------------

    @Test
    @DisplayName("GET /users — returns an array")
    void should_listAllUsers() throws Exception {
        when(service.findAll()).thenReturn(List.of(userFixture(1L), userFixture(2L)));

        mvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("GET /users/{id} — returns the user when found")
    void should_getUserById() throws Exception {
        when(service.findById(1L)).thenReturn(userFixture(1L));

        mvc.perform(get("/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.createdAt", notNullValue()));
    }

    @Test
    @DisplayName("GET /users/{id} — 404 USER_NOT_FOUND when absent")
    void should_return404_whenUserMissing() throws Exception {
        when(service.findById(99L))
                .thenThrow(new NotFoundException(ErrorCode.USER_NOT_FOUND, "User 99 was not found."));

        mvc.perform(get("/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/users/99"));
    }

    // ---- Spec 01 §6 — partial update (fullName + role only) ------------------

    @Test
    @DisplayName("POST /users/update/{id} — happy path, only fullName + role changed in service call")
    void should_updateUser() throws Exception {
        User updated = userFixture(1L);
        updated.setFullName("Alice Admin");
        updated.setRole(Role.ADMIN);
        when(service.update(eq(1L), any(UpdateUserRequest.class))).thenReturn(updated);

        var body = json.writeValueAsString(new UpdateUserRequest("Alice Admin", Role.ADMIN));

        mvc.perform(post("/users/update/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("POST /users/update/{id} — extra fields (e.g. username) are silently ignored per spec")
    void should_silentlyIgnoreUnknownUpdateFields() throws Exception {
        User updated = userFixture(1L);
        updated.setFullName("Alice Admin");
        updated.setRole(Role.ADMIN);
        when(service.update(eq(1L), any(UpdateUserRequest.class))).thenReturn(updated);

        // Client sends username + email + password — DTO doesn't declare them, so they vanish.
        String body = """
                {
                  "fullName": "Alice Admin",
                  "role": "ADMIN",
                  "username": "evil-rename",
                  "email": "evil@example.com",
                  "password": "ignored"
                }
                """;

        mvc.perform(post("/users/update/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // Response username is the fixture's original — extra field was discarded by Jackson.
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.username", not("evil-rename")));
    }

    @Test
    @DisplayName("POST /users/update/{id} — 404 USER_NOT_FOUND when target missing")
    void should_return404_whenUpdateTargetMissing() throws Exception {
        when(service.update(anyLong(), any(UpdateUserRequest.class)))
                .thenThrow(new NotFoundException(ErrorCode.USER_NOT_FOUND, "User 99 was not found."));

        var body = json.writeValueAsString(new UpdateUserRequest("Alice Admin", Role.ADMIN));

        mvc.perform(post("/users/update/99").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---- Spec 01 §7 — delete --------------------------------------------------

    @Test
    @DisplayName("DELETE /users/{id} — 204 on success")
    void should_deleteUser() throws Exception {
        doNothing().when(service).delete(1L);

        mvc.perform(delete("/users/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /users/{id} — 404 USER_NOT_FOUND when absent")
    void should_return404_whenDeleteTargetMissing() throws Exception {
        doThrow(new NotFoundException(ErrorCode.USER_NOT_FOUND, "User 99 was not found."))
                .when(service).delete(99L);

        mvc.perform(delete("/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---- Bonus — NoResourceFoundException handler (folded in from slice 1) ----

    @Test
    @DisplayName("GET /no-such-endpoint — 404 NOT_FOUND from NoResourceFoundException handler arm")
    void should_return404_whenEndpointMissing() throws Exception {
        mvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
