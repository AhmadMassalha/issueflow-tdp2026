package com.att.tdp.issueflow.users.repository;

import com.att.tdp.issueflow.users.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence boundary for {@link User}.
 *
 * <p>Existence checks ({@code existsByUsername}, {@code existsByEmail}) are used
 * by {@code UserService} to pre-empt unique-constraint violations and return the
 * spec'd {@code USER_DUPLICATE_*} codes instead of the generic
 * {@code DATA_INTEGRITY_VIOLATION}.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
