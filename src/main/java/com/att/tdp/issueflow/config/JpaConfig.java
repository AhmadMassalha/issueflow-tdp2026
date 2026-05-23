package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Activates JPA auditing (so {@code @CreatedDate} / {@code @LastModifiedDate} on
 * {@code BaseEntity} are populated) and explicit transaction management.
 */
@Configuration
@EnableJpaAuditing
@EnableTransactionManagement
public class JpaConfig {
}
