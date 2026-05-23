package com.att.tdp.issueflow.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.auth.api.AuthController;
import com.att.tdp.issueflow.auth.api.LoginRequest;
import com.att.tdp.issueflow.auth.api.LoginResponse;
import com.att.tdp.issueflow.auth.service.AuthService;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.users.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HTTP-layer coverage for {@code /auth/login}. The token-protected endpoints
 * ({@code /auth/logout}, {@code /auth/me}) need a populated security context
 * and are covered end-to-end in {@code SecurityIntegrationTest} instead.
 *
 * <p>Filters are disabled to keep this slice focused on controller validation
 * + envelope shape (see {@code .cursor/rules/30-testing.mdc} Gotchas section).
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AuthService auth;

    @MockitoBean
    private UserService usersService; // unused here but required to satisfy AuthController's constructor

    @Test
    @DisplayName("POST /auth/login — 200 with accessToken+tokenType+expiresIn on valid creds")
    void should_login_andReturnToken() throws Exception {
        when(auth.login(any(LoginRequest.class)))
                .thenReturn(LoginResponse.bearer("eyJhbGciOiJIUzI1NiJ9.fake.token", 3600));

        String body = json.writeValueAsString(new LoginRequest("alice", "s3cret-pw"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("eyJhbGciOiJIUzI1NiJ9.fake.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    @DisplayName("POST /auth/login — 401 AUTH_INVALID_CREDENTIALS when service throws InvalidCredentialsException")
    void should_return401_whenCredentialsInvalid() throws Exception {
        when(auth.login(any(LoginRequest.class)))
                .thenThrow(new AuthService.InvalidCredentialsException());

        String body = json.writeValueAsString(new LoginRequest("alice", "wrong-pw"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("POST /auth/login — same 401 AUTH_INVALID_CREDENTIALS whether username unknown or password wrong (spec 02 §2 — no user enumeration)")
    void should_return_sameShape_forBothFailureModes() throws Exception {
        when(auth.login(any(LoginRequest.class)))
                .thenThrow(new AuthService.InvalidCredentialsException());

        String body = json.writeValueAsString(new LoginRequest("nobody", "any-pw"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
    }

    @Test
    @DisplayName("POST /auth/login — 400 VALIDATION_FAILED when username is blank")
    void should_return400_whenUsernameBlank() throws Exception {
        String body = json.writeValueAsString(new LoginRequest("", "any-pw"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(auth, never()).login(any());
    }
}
