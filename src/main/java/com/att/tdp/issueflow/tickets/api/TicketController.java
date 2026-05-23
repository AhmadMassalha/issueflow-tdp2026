package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.tickets.service.TicketService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for {@code /tickets}.
 *
 * <p>Endpoint list per spec 04:
 * <ul>
 *   <li>{@code GET    /tickets?projectId=…}    — list by project (spec §12)</li>
 *   <li>{@code GET    /tickets/{ticketId}}     — fetch one</li>
 *   <li>{@code POST   /tickets}                — create (200 OK)</li>
 *   <li>{@code PATCH  /tickets/{ticketId}}     — partial update with version check</li>
 *   <li>{@code DELETE /tickets/{ticketId}}     — hard delete now → soft in slice 9</li>
 * </ul>
 *
 * <p><b>{@code projectId} required (spec §12):</b> declared as
 * {@code @RequestParam Long projectId} with no default. Spring throws
 * {@code MissingServletRequestParameterException} when absent, which the
 * existing {@code GlobalExceptionHandler.handleMissingParam} arm maps to 400
 * {@code MISSING_PARAMETER} with the field name. No additional handling here.
 *
 * <p><b>POST returns 200 OK</b> (not 201) for consistency with
 * {@code /users} (Session-02 D3) and {@code /projects} (Session-04). The
 * README locks 200 for the create path on every entity.
 *
 * <p><b>Optimistic locking on PATCH:</b> the client MUST include {@code version}
 * in the request body (spec §6). Missing → 400 {@code VERSION_REQUIRED}, stale
 * → 409 {@code TICKET_VERSION_CONFLICT}. Both checks live in the service.
 *
 * <p><b>RBAC:</b> all endpoints inherit "authenticated" from the global
 * {@code SecurityConfig} chain. No per-endpoint {@code @PreAuthorize} —
 * tickets are the working surface, every developer needs read/write access.
 * Documented as a deliberate non-decision in prompts.md.
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService service;

    @GetMapping
    public List<TicketResponse> list(@RequestParam Long projectId) {
        return service.findByProjectId(projectId).stream().map(TicketResponse::from).toList();
    }

    @GetMapping("/{ticketId}")
    public TicketResponse get(@PathVariable Long ticketId) {
        return TicketResponse.from(service.findById(ticketId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest req) {
        return TicketResponse.from(service.create(req));
    }

    @PatchMapping("/{ticketId}")
    public TicketResponse update(@PathVariable Long ticketId,
                                 @Valid @RequestBody PatchTicketRequest req) {
        return TicketResponse.from(service.update(ticketId, req));
    }

    @DeleteMapping("/{ticketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long ticketId) {
        service.delete(ticketId);
    }
}
