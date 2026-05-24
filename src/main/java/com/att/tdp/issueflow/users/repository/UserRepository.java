package com.att.tdp.issueflow.users.repository;

import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.users.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link User}.
 *
 * <p>Existence checks ({@code existsByUsername}, {@code existsByEmail}) are used
 * by {@code UserService} to pre-empt unique-constraint violations and return the
 * spec'd {@code USER_DUPLICATE_*} codes instead of the generic
 * {@code DATA_INTEGRITY_VIOLATION}.
 *
 * <p>{@link #findMentionedUsersByLoweredUsernames(Collection)} is the
 * mention extractor's lookup (spec 09 + Session 10 D2) — case-insensitive
 * username resolution in one batched query. Lives here because it returns
 * the {@code User}-shaped projection.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Case-insensitive batch lookup used by the mention extractor.
     * Callers must lowercase their input first (the service does the
     * lowercasing once, so we don't pay {@code lower(?)} per element in
     * the IN list). Unknown handles produce no row (silently ignored per
     * spec 09 §Extraction).
     *
     * <p>Returns the projection directly so the service can hand it
     * straight to {@code CommentResponse.from(comment, summaries)}
     * without an intermediate {@code User} round-trip.
     */
    @Query("""
           select new com.att.tdp.issueflow.mentions.api.MentionedUserSummary(
               u.id, u.username, u.fullName)
           from User u
           where lower(u.username) in :loweredUsernames
           """)
    List<MentionedUserSummary> findMentionedUsersByLoweredUsernames(
            @Param("loweredUsernames") Collection<String> loweredUsernames);
}
