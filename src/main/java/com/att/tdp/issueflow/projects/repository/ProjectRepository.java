package com.att.tdp.issueflow.projects.repository;

import com.att.tdp.issueflow.projects.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence boundary for {@link Project}.
 *
 * <p>The two {@code existsBy…} queries support the pre-emptive duplicate check
 * in {@link com.att.tdp.issueflow.projects.service.ProjectService}: the simple
 * one for {@code POST}, the {@code …AndIdNot} one for {@code PATCH} so renaming
 * to your own current name doesn't false-trigger the conflict.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndNameAndIdNot(Long ownerId, String name, Long id);
}
