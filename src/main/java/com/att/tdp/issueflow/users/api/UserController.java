package com.att.tdp.issueflow.users.api;

import com.att.tdp.issueflow.users.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * REST endpoints for {@code /users}.
 *
 * <p>Endpoint list is dictated by the README (Spec 01):
 * <ul>
 *   <li>{@code GET    /users}                 — list all</li>
 *   <li>{@code GET    /users/{id}}            — fetch one</li>
 *   <li>{@code POST   /users}                 — create (200 OK on success, see below)</li>
 *   <li>{@code POST   /users/update/{id}}     — partial update of {@code fullName}+{@code role}</li>
 *   <li>{@code DELETE /users/{id}}            — hard delete</li>
 * </ul>
 *
 * <p><b>Status-code divergence from REST convention (Session-02 D3):</b>
 * Spec 01 §1 specifies {@code 200 OK} on create. REST convention says {@code 201
 * Created}. We follow the spec; reviewers should know we considered the
 * alternative.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping
    public List<UserResponse> list() {
        return service.findAll().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable Long userId) {
        return UserResponse.from(service.findById(userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK) // see class JavaDoc — spec 01 §1
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return UserResponse.from(service.create(req));
    }

    @PostMapping("/update/{userId}")
    public UserResponse update(@PathVariable Long userId, @Valid @RequestBody UpdateUserRequest req) {
        return UserResponse.from(service.update(userId, req));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long userId) {
        service.delete(userId);
    }
}
