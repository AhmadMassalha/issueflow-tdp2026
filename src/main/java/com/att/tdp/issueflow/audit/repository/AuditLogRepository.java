package com.att.tdp.issueflow.audit.repository;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence boundary for {@link AuditLog}.
 *
 * <p>{@link JpaSpecificationExecutor} powers the spec 06 §6 dynamic filter
 * + pagination query. {@link com.att.tdp.issueflow.audit.repository.AuditLogSpecifications}
 * builds the {@code Specification<AuditLog>} from the optional filter
 * arguments.
 *
 * <p>No derived queries here — every read goes through {@code findAll(Specification, Pageable)}.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
