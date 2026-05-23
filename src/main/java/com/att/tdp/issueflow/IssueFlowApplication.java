package com.att.tdp.issueflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} picks up record-style property bindings
 * (e.g. {@code JwtProperties}, {@code AdminSeedProperties}) so we don't have to
 * list each in an {@code @EnableConfigurationProperties} attribute.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IssueFlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(IssueFlowApplication.class, args);
	}

}
