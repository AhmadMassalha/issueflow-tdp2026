package com.att.tdp.issueflow.common.web;

import com.att.tdp.issueflow.common.exception.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central translator from exception → {@link ApiError} JSON.
 *
 * <p>Order of resolution:
 * <ol>
 *   <li>Domain exceptions (subclasses of {@link DomainException}) — preserve the carrier {@link ErrorCode}.</li>
 *   <li>Framework exceptions — mapped to generic codes ({@code VALIDATION_FAILED}, {@code MALFORMED_REQUEST}, …).</li>
 *   <li>Catch-all {@link Exception} → 500 {@code INTERNAL_ERROR}; logged at ERROR.</li>
 * </ol>
 *
 * <p>Slice 3 added Spring Security arms: {@link AccessDeniedException} → 403
 * {@code AUTH_FORBIDDEN}, {@link AuthenticationException} → 401
 * {@code AUTH_TOKEN_INVALID}. Both are reached via the custom
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} in
 * {@code SecurityConfig}, which delegates to the {@code HandlerExceptionResolver}
 * so this advice still runs.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ---- Domain exceptions ------------------------------------------------------

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest req) {
        HttpStatus status = ex.code().defaultStatus();
        ApiError body = ApiError.of(status, ex.code(), ex.getMessage(), req.getRequestURI(), ex.details());
        log.debug("Domain exception {} on {}: {}", ex.code(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    // ---- Bean Validation on @RequestBody ----------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldIssue> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldIssue(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.", req.getRequestURI(), details);
        return ResponseEntity.badRequest().body(body);
    }

    // ---- Bean Validation on @PathVariable / @RequestParam -----------------------

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldIssue> details = ex.getConstraintViolations().stream()
                .map(this::toFieldIssue)
                .collect(Collectors.toList());
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.", req.getRequestURI(), details);
        return ResponseEntity.badRequest().body(body);
    }

    private ApiError.FieldIssue toFieldIssue(ConstraintViolation<?> v) {
        // propertyPath like "controllerMethod.argName" — keep just the last segment.
        String path = v.getPropertyPath().toString();
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return new ApiError.FieldIssue(field, v.getMessage());
    }

    // ---- Malformed / missing input ----------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        // Custom JsonDeserializers (e.g. RoleJsonDeserializer) may throw a DomainException;
        // Jackson wraps it in JsonMappingException → Spring wraps in HttpMessageNotReadableException.
        // Unwrap and delegate so feature-specific codes (e.g. USER_INVALID_ROLE) survive.
        Throwable cur = ex.getCause();
        while (cur != null && cur != ex) {
            if (cur instanceof DomainException de) {
                return handleDomain(de, req);
            }
            cur = cur.getCause();
        }
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "Malformed request body.", req.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER,
                "Missing required parameter: " + ex.getParameterName(), req.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Parameter '" + ex.getName() + "' has an invalid value.", req.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    // ---- Persistence ------------------------------------------------------------

    /**
     * Race window between a {@code TicketService}-style version pre-check and JPA flush.
     *
     * <p>The service layer typically pre-checks the client-supplied {@code version} against
     * the entity's {@code @Version} field and throws an immediate
     * {@code Conflict(TICKET_VERSION_CONFLICT)} (or {@code COMMENT_VERSION_CONFLICT}, etc.)
     * — see ADR 0001. The exception handled here covers the (rare) case where another
     * transaction commits between the pre-check and the JPA flush at commit time.
     *
     * <p>The persistent class is inspected so feature-specific codes are emitted even on
     * the safety-net path. Falls back to the generic {@code VERSION_CONFLICT} for entities
     * not covered (currently none — slice 6 adds Comment).
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleOptimisticLock(Exception ex, HttpServletRequest req) {
        ErrorCode code = ErrorCode.VERSION_CONFLICT;
        if (ex instanceof ObjectOptimisticLockingFailureException oolfe) {
            String entityName = oolfe.getPersistentClassName();
            if (entityName != null) {
                String simple = entityName.substring(entityName.lastIndexOf('.') + 1);
                code = switch (simple) {
                    case "Ticket" -> ErrorCode.TICKET_VERSION_CONFLICT;
                    case "Comment" -> ErrorCode.COMMENT_VERSION_CONFLICT;
                    default -> ErrorCode.VERSION_CONFLICT;
                };
            }
        }
        ApiError body = ApiError.of(HttpStatus.CONFLICT, code,
                "The entity was modified by another transaction. Re-fetch and retry.", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Generic unique-constraint / FK fallback. Feature services should pre-empt this by
     * catching the duplicate at insertion time and throwing a {@link DomainException}
     * with a feature-specific code (e.g. {@code USER_DUPLICATE_USERNAME}).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("DataIntegrityViolation on {}: {}", req.getRequestURI(), rootMessage(ex));
        ApiError body = ApiError.of(HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                "Database integrity violation.", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // ---- Security ---------------------------------------------------------------

    /**
     * Authenticated user lacked the required role / authority. Mapped to 403
     * {@code AUTH_FORBIDDEN} (spec 02 §7).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.FORBIDDEN, ErrorCode.AUTH_FORBIDDEN,
                "Access denied: insufficient role for " + req.getMethod() + " " + req.getRequestURI(),
                req.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Reached when Spring Security's filter chain rejects a request that
     * required authentication and didn't have it (e.g. no header on a
     * protected endpoint). Spec 02 §3/§4 require 401 {@code AUTH_TOKEN_INVALID}.
     *
     * <p>Token-specific failures thrown from {@code JwtAuthenticationFilter} are
     * domain exceptions and handled by {@link #handleDomain}; this arm is only
     * for Spring's own {@link AuthenticationException} types.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_TOKEN_INVALID,
                "Authentication is required for " + req.getMethod() + " " + req.getRequestURI(),
                req.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    // ---- Routing ----------------------------------------------------------------

    /**
     * No handler matched the request path. Without this arm the default static-resource
     * resolver throws {@link NoResourceFoundException} and the catch-all would map it
     * to 500 — wrong shape for a typo'd URL.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                "No endpoint matches " + req.getMethod() + " " + req.getRequestURI(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // ---- Multipart --------------------------------------------------------------

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                "Uploaded payload exceeds the maximum allowed size.", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * Symmetric counterpart to {@link #handleMissingParam}: the equivalent
     * for multipart {@code file} parts that the client forgot to attach.
     * Spring throws this when {@code @RequestParam("file") MultipartFile}
     * is declared but the request had no part with that name. Mapped to
     * 400 {@code MISSING_PARAMETER} for shape-consistency with the
     * non-multipart "missing required param" case.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER,
                "Missing required multipart part: " + ex.getRequestPartName(), req.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    // ---- Last-resort ------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred.", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }
}
