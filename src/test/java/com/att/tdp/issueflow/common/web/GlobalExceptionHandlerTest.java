package com.att.tdp.issueflow.common.web;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.ForbiddenException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.exception.VersionConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wires a dummy controller through the real {@link GlobalExceptionHandler} and asserts
 * the JSON envelope shape + HTTP status for every code path the handler covers.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ThrowingController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    // ---- Domain exceptions ------------------------------------------------------

    @Test
    @DisplayName("NotFoundException -> 404 with feature code")
    void should_map_notFound_to404() throws Exception {
        mvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("user 42 not found"))
                .andExpect(jsonPath("$.path").value("/throw/not-found"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("ConflictException -> 409 with feature code")
    void should_map_conflict_to409() throws Exception {
        mvc.perform(get("/throw/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_DONE_IS_IMMUTABLE"));
    }

    @Test
    @DisplayName("ValidationException -> 422 (default) with field details")
    void should_map_validation_to422() throws Exception {
        mvc.perform(get("/throw/validation"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"))
                .andExpect(jsonPath("$.details[0].field").value("blockedBy"))
                .andExpect(jsonPath("$.details[0].issue").value("creates cycle"));
    }

    @Test
    @DisplayName("ForbiddenException -> 403 with feature code")
    void should_map_forbidden_to403() throws Exception {
        mvc.perform(get("/throw/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_FORBIDDEN"));
    }

    @Test
    @DisplayName("VersionConflictException -> 409 with feature code")
    void should_map_versionConflict_to409() throws Exception {
        mvc.perform(get("/throw/version"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VERSION_CONFLICT"));
    }

    // ---- Bean Validation --------------------------------------------------------

    @Test
    @DisplayName("@Valid @RequestBody failure -> 400 VALIDATION_FAILED with details")
    void should_map_beanValidation_to400() throws Exception {
        String bodyMissingFields = "{\"email\":\"not-an-email\"}";

        mvc.perform(post("/throw/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyMissingFields))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("Malformed JSON -> 400 MALFORMED_REQUEST")
    void should_map_unreadable_to400() throws Exception {
        mvc.perform(post("/throw/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("Missing required @RequestParam -> 400 MISSING_PARAMETER")
    void should_map_missingParam_to400() throws Exception {
        mvc.perform(get("/throw/need-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @DisplayName("Type-mismatch on @RequestParam -> 400 VALIDATION_FAILED")
    void should_map_typeMismatch_to400() throws Exception {
        mvc.perform(get("/throw/need-param").param("n", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- Persistence / optimistic locking --------------------------------------

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException -> 409 VERSION_CONFLICT")
    void should_map_optimisticLock_to409() throws Exception {
        mvc.perform(get("/throw/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---- Last-resort fallback ---------------------------------------------------

    @Test
    @DisplayName("Unhandled exception -> 500 INTERNAL_ERROR")
    void should_map_unknown_to500() throws Exception {
        mvc.perform(get("/throw/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    // ---- Dummy controller that throws each exception ---------------------------

    @RestController
    @RequestMapping("/throw")
    static class ThrowingController {

        @GetMapping("/not-found")
        void notFound() {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND, "user 42 not found");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException(ErrorCode.TICKET_DONE_IS_IMMUTABLE,
                    "Ticket 42 is DONE and cannot be modified.");
        }

        @GetMapping("/validation")
        void validation() {
            throw new ValidationException(ErrorCode.DEPENDENCY_CYCLE,
                    "Dependency would create a cycle.",
                    java.util.List.of(new ApiError.FieldIssue("blockedBy", "creates cycle")));
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException(ErrorCode.COMMENT_FORBIDDEN,
                    "You can only edit your own comments.");
        }

        @GetMapping("/version")
        void version() {
            throw new VersionConflictException(ErrorCode.TICKET_VERSION_CONFLICT,
                    "Ticket was modified by another transaction.");
        }

        @PostMapping("/validate")
        void validateBody(@Valid @RequestBody Payload body) {
            // never executes — validation fails first
        }

        @GetMapping("/need-param")
        void needParam(@RequestParam("n") Integer n) {
            // never executes
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("FakeEntity", 1L);
        }

        @GetMapping("/unknown")
        void unknown() {
            throw new IllegalStateException("boom");
        }
    }

    @Data
    static class Payload {
        @NotBlank
        private String name;
        @Email
        private String email;
    }
}
