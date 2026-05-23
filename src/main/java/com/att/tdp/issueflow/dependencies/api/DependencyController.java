package com.att.tdp.issueflow.dependencies.api;

import com.att.tdp.issueflow.dependencies.repository.BlockerSummary;
import com.att.tdp.issueflow.dependencies.service.DependencyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for ticket-blocker dependencies (spec 07).
 *
 * <ul>
 *   <li>{@code POST   /tickets/{ticketId}/dependencies}  — add an edge</li>
 *   <li>{@code GET    /tickets/{ticketId}/dependencies}  — list blockers</li>
 *   <li>{@code DELETE /tickets/{ticketId}/dependencies/{blockerId}}</li>
 * </ul>
 *
 * <p><b>RBAC (Session 08 D6):</b> any authenticated user. Matches the
 * Project/Ticket default — dependencies are PM/dev working data, not
 * an ADMIN-only operation.
 *
 * <p>Response shapes:
 * <ul>
 *   <li>POST → 201 with an {@code AddDependencyResponse} envelope so
 *       the client knows the surrogate id for subsequent DELETE / audit
 *       cross-reference.</li>
 *   <li>GET → 200 with {@code List<BlockerSummary>} (each {@code id,
 *       title, status}) — spec §5 verbatim.</li>
 *   <li>DELETE → 204.</li>
 * </ul>
 */
@RestController
@RequestMapping("/tickets/{ticketId}/dependencies")
@RequiredArgsConstructor
public class DependencyController {

    private final DependencyService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddDependencyResponse add(@PathVariable Long ticketId,
                                     @Valid @RequestBody AddDependencyRequest req) {
        var saved = service.add(ticketId, req.blockedBy());
        return new AddDependencyResponse(saved.getId(), saved.getTicketId(), saved.getBlockerId());
    }

    @GetMapping
    public List<BlockerSummary> list(@PathVariable Long ticketId) {
        return service.listBlockers(ticketId);
    }

    @DeleteMapping("/{blockerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long ticketId, @PathVariable Long blockerId) {
        service.remove(ticketId, blockerId);
    }

    /**
     * Minimal response for {@code POST}. Echoes the composite plus the
     * surrogate {@code id} so the client can DELETE by either pair-coord
     * (matching the spec's DELETE URL) or by id (matching the audit log).
     */
    public record AddDependencyResponse(Long id, Long ticketId, Long blockerId) {
    }
}
