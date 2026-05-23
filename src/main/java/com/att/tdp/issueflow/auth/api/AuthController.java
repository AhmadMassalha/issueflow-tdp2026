package com.att.tdp.issueflow.auth.api;

import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.auth.service.AuthService;
import com.att.tdp.issueflow.users.api.UserResponse;
import com.att.tdp.issueflow.users.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints per spec 02:
 * <ul>
 *   <li>{@code POST /auth/login} — public; returns access token (200)</li>
 *   <li>{@code POST /auth/logout} — authenticated; revokes the current token (200)</li>
 *   <li>{@code GET /auth/me} — authenticated; returns the current user's profile</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService auth;
    private final UserService usersService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        // The filter has already validated the token by the time we get here; the header
        // is guaranteed to start with "Bearer ". Belt-and-suspenders null check anyway.
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            auth.logout(header.substring(BEARER_PREFIX.length()).trim());
        }
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal IssueFlowUserPrincipal principal) {
        // Re-fetch through the service so fullName / email / timestamps come from the DB.
        return UserResponse.from(usersService.findById(principal.id()));
    }
}
