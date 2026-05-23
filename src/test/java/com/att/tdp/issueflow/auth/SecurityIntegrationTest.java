package com.att.tdp.issueflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.users.api.CreateUserRequest;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end coverage for spec 02 §3–§7: real {@code SecurityFilterChain}, real
 * {@code JwtAuthenticationFilter}, real {@code @PreAuthorize}, real DB
 * (H2 in PostgreSQL mode via {@code src/test/resources/application.yaml}).
 *
 * <p>Each test:
 * <ol>
 *   <li>Seeds users directly through the repository (bypassing
 *       {@code @PreAuthorize}, since {@code AdminSeeder} is disabled in tests).</li>
 *   <li>POSTs to {@code /auth/login} to mint a real JWT.</li>
 *   <li>Calls a protected endpoint with that token.</li>
 *   <li>Asserts the spec-required status + envelope shape.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User dev;

    @BeforeEach
    void seed() {
        users.deleteAll();
        admin = persist("admin", "admin@example.com", Role.ADMIN, "admin-pw");
        dev = persist("dev", "dev@example.com", Role.DEVELOPER, "dev-pw");
    }

    private User persist(String username, String email, Role role, String pw) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(pw));
        return users.save(u);
    }

    private String login(String username, String password) throws Exception {
        String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = json.readTree(result.getResponse().getContentAsString());
        return payload.get("accessToken").asText();
    }

    // ---- Spec 02 §3 — every protected endpoint requires a valid bearer token

    @Test
    @DisplayName("GET /users without Authorization → 401 AUTH_TOKEN_INVALID")
    void should_return401_whenNoToken() throws Exception {
        mvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("GET /users with valid token → 200")
    void should_listUsers_whenAuthenticated() throws Exception {
        String token = login("dev", "dev-pw");

        mvc.perform(get("/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'admin')]").exists())
                .andExpect(jsonPath("$[?(@.username == 'dev')]").exists());
    }

    // ---- Spec 02 §4 — malformed token

    @Test
    @DisplayName("Garbage Bearer token → 401 AUTH_TOKEN_INVALID")
    void should_return401_whenTokenMalformed() throws Exception {
        mvc.perform(get("/users").header("Authorization", "Bearer not.a.real.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    // ---- Spec 02 §1 / §2 — login flow

    @Test
    @DisplayName("POST /auth/login with wrong password → 401 AUTH_INVALID_CREDENTIALS")
    void should_return401_whenPasswordWrong() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"WRONG\"}";

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /auth/login with unknown username → same shape as wrong-password (spec 02 §2 — no enumeration)")
    void should_return401_withSameShape_whenUsernameUnknown() throws Exception {
        String body = "{\"username\":\"nobody\",\"password\":\"any\"}";

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
    }

    // ---- Spec 02 §5 — logout / revoke

    @Test
    @DisplayName("POST /auth/logout revokes the current token; reuse → 401 AUTH_TOKEN_REVOKED")
    void should_revokeToken_onLogout() throws Exception {
        String token = login("dev", "dev-pw");

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"));
    }

    // ---- Spec 02 §6 — /auth/me

    @Test
    @DisplayName("GET /auth/me returns the current user looked up from the JWT sub claim")
    void should_returnCurrentUser_onMe() throws Exception {
        String token = login("dev", "dev-pw");

        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dev.getId()))
                .andExpect(jsonPath("$.username").value("dev"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ---- Spec 02 §7 / ADR 0005 — RBAC on /users

    @Test
    @DisplayName("DEVELOPER calling DELETE /users/{id} → 403 AUTH_FORBIDDEN")
    void should_return403_whenDeveloperDeletesUser() throws Exception {
        String devToken = login("dev", "dev-pw");

        mvc.perform(delete("/users/" + admin.getId())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("ADMIN calling DELETE /users/{id} → 204")
    void should_allowAdminDelete() throws Exception {
        String adminToken = login("admin", "admin-pw");

        mvc.perform(delete("/users/" + dev.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(users.findById(dev.getId())).isEmpty();
    }

    @Test
    @DisplayName("DEVELOPER calling POST /users → 403 AUTH_FORBIDDEN (ADR 0005)")
    void should_return403_whenDeveloperCreatesUser() throws Exception {
        String devToken = login("dev", "dev-pw");

        String body = json.writeValueAsString(new CreateUserRequest(
                "newbie", "newbie@example.com", "Newbie One", Role.DEVELOPER, "newbie-pw"));

        mvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("ADMIN calling POST /users → 200 (creates the user)")
    void should_allowAdminCreate() throws Exception {
        String adminToken = login("admin", "admin-pw");

        String body = json.writeValueAsString(new CreateUserRequest(
                "newbie", "newbie@example.com", "Newbie One", Role.DEVELOPER, "newbie-pw"));

        mvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newbie"));

        assertThat(users.findByUsername("newbie")).isPresent();
    }
}
